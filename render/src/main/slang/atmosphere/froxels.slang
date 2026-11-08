// Volumetric fog in froxels (VolumetricFog; Wronski 2014, "Volumetric Fog", SIGGRAPH Advances; Hillaire 2015,
// "Physically Based and Unified Volumetric Rendering in Frostbite"): atmosphere-fog's media (its height fog, fog
// volumes and indoor zones, the same function its hook integrates) sampled in a grid over the view's frustum, lit by
// every light of the view's light list with its shadows (the sun's cascades: light shafts) and by the sky's ambient
// light, then integrated front to back with Hillaire 2015's energy-conserving form. The fog's hook (atmosphere/fog.slang)
// reads the result up to the grid's reach and continues from there.
import atmosphere;
import atmosphere.fog;
import meshlets.common;
import lights;

struct FroxelPush
{
    View* view;
    AtmosphereFrame* atmosphere;  // ambientMain: the atmosphere (the sky-view table's frame)
    LightListHeader* lights;      // the view's light list (lit 1), or any valid address
    float4* ambient;              // the sky's isotropic radiance (rgb), written by ambientMain
    uint fogRecord;               // the fog hook's record: its byte offset in the view's hook block
    uint media;                   // storage slot: per froxel, in-scattered light per metre (rgb) and extinction (a)
    uint integrated;              // storage slot: from the camera, in-scattered light (rgb) and transmittance (a)
    uint size;                    // the grid: width | height << 16
    uint slices;
    uint pixels;                  // the view's size: width | height << 16
    float near;                   // metres: the first slice's end
    float far;                    // metres: the last slice's end
    float cameraHeight;           // the camera's height over the fog's base height, metres
    uint lit;                     // 1: the light list lights the fog (shadows included); 0: the fog's own sun
    uint sky;                     // ambientMain: sampled slot of the sky-view table (0: no atmosphere)
    uint skySize;                 // its width | height << 16
};

[[vk::push_constant]]
ConstantBuffer<FroxelPush> push;

/** Where slice `i` ends, metres: near, then exponentially to far. */
float sliceEnd(uint i)
{
    return i == 0 ? push.near : push.near * pow(push.far / push.near, float(i) / float(push.slices - 1));
}

/** The fog's record in the view's hook block. */
AtmosphereFogParameters* fogParameters()
{
    return (AtmosphereFogParameters*) ((uint64_t) push.view[0].hooks + (uint64_t) push.fogRecord);
}

/**
 * The sky's isotropic radiance: the mean of the sky-view table over the sphere (the lit ground below the horizon
 * included), 256 spherical Fibonacci directions on one workgroup. What the fog scatters of the sky's light.
 */
groupshared float3 skySum[64];

[shader("compute")]
[numthreads(64, 1, 1)]
void ambientMain(uint3 id : SV_DispatchThreadID)
{
    float3 sum = 0;
    if (push.sky != 0)
    {
        AtmosphereFrame* a = push.atmosphere;
        float3 camera = cameraPosition(a);
        float r = length(camera);
        float least = a[0].bottomRadius + 0.0005;
        camera = r < least ? camera / max(r, 1e-6) * least : camera;
        SkyFrame frame = skyFrame(a, camera);
        float2 size = tableSize(push.skySize);
        for (uint k = 0; k < 4; k++)
        {
            uint index = id.x * 4 + k;
            float z = 1 - (2 * float(index) + 1) / 256.0;
            float phi = float(index) * 2.39996323;
            float s = sqrt(max(1 - z * z, 0));
            float3 d = float3(s * cos(phi), z, s * sin(phi));
            float2 uv = skyViewTexel(skyViewUv(frame, d), size);
            sum += textures[push.sky].SampleLevel(samplers[LINEAR_REPEAT], uv, 0).rgb;
        }
    }
    skySum[id.x] = sum;
    GroupMemoryBarrierWithGroupSync();
    for (uint stride = 32; stride > 0; stride /= 2)
    {
        if (id.x < stride)
        {
            skySum[id.x] += skySum[id.x + stride];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    if (id.x == 0)
    {
        push.ambient[0] = float4(skySum[0] / 256.0, 1);
    }
}

/**
 * Each froxel's media and the light they scatter towards the camera, per metre: at its centre (along the ray through
 * its tile's middle, halfway through its slice), the fog's extinction and scattering, lit by every light of the
 * light list reaching it (after its shadow: the sun's cascades cut light shafts) through the Henyey-Greenstein phase
 * of the fog's anisotropy, and by the sky's ambient light.
 */
[shader("compute")]
[numthreads(4, 4, 4)]
void lightMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size) || id.z >= push.slices)
    {
        return;
    }
    float2 pixels = float2(push.pixels & 0xffff, push.pixels >> 16);
    float2 pixel = (float2(id.xy) + 0.5) / float2(size) * pixels;
    float2 ndc = pixel / pixels * 2 - 1;
    float4 world = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    float3 dir = normalize(world.xyz / world.w);
    float start = id.z == 0 ? 0 : sliceEnd(id.z - 1);
    float distance = 0.5 * (start + sliceEnd(id.z));
    float3 p = dir * distance;
    AtmosphereFogParameters* fog = fogParameters();
    float3 scattering;
    float sigma = fogMediumAt(fog, p, push.cameraHeight, scattering);
    float3 s = 0;
    if (sigma > 0)
    {
        float3 light = push.ambient[0].rgb;
        float g = fog[0].anisotropy;
        if (push.lit != 0)
        {
            LightListHeader header = loadHeader(push.lights);
            float depth = mul(push.view[0].viewProjection, float4(p, 1)).w;
            uint2 at = uint2(pixel);
            ClusterLightList list = ClusterLightList(header, at, depth);
            for (uint i = 0; i < list.count(); i++)
            {
                float3 toLight;
                float3 e = lightIrradiance(header, list.light(i), p, -dir, at, depth, toLight);
                light += e * fogPhase(dot(toLight, dir), g);
            }
        }
        else
        {
            float3 sun = fog[0].sunDirection;
            float l = length(sun);
            if (l > 0)
            {
                light += fog[0].sunIlluminance * fogPhase(dot(sun / l, dir), g);
            }
        }
        s = scattering * light;
    }
    volumeImages[push.media][id] = float4(s, sigma);
}

/** Each froxel column integrated front to back: in-scattered light and transmittance from the camera to each slice's end. */
[shader("compute")]
[numthreads(8, 8, 1)]
void integrateMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    float3 light = 0;
    float transmittance = 1;
    float start = 0;
    for (uint i = 0; i < push.slices; i++)
    {
        float end = sliceEnd(i);
        float dt = end - start;
        float4 m = volumeImages[push.media][uint3(id.xy, i)];
        float t = exp(-m.a * dt);
        light += transmittance * m.rgb * (m.a > 1e-9 ? (1 - t) / m.a : dt);
        transmittance *= t;
        volumeImages[push.integrated][uint3(id.xy, i)] = float4(light, transmittance);
        start = end;
    }
}
