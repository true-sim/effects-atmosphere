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
    uint samples;                 // lightMain: samples per froxel, at positions jittered within it (1, history 0:
                                  // its centre)
    uint frame;                   // lightMain: the frame's number, which turns the jitter each frame
    float history;                // how much of last frame's froxels each keeps (0: none; temporalMain: 0 when
                                  // there are none)
    uint last;                    // temporalMain: sampled slot of last frame's froxels (media and light)
};

[[vk::push_constant]]
ConstantBuffer<FroxelPush> push;

/** Where slice `i` ends, metres: near, then exponentially to far. */
float sliceEnd(uint i)
{
    return i == 0 ? push.near : push.near * pow(push.far / push.near, float(i) / float(push.slices - 1));
}

/** Where slice coordinate `c` lies along the ray, metres (slice i spans i to i + 1): sliceEnd's inverse. */
float sliceCoordinate(float distance)
{
    return distance < push.near ? distance / push.near
        : 1 + log(distance / push.near) / log(push.far / push.near) * float(push.slices - 1);
}

/**
 * Froxel `id` at `offset` within it (0 to 1 each way; 0.5: its centre): the camera-relative point there, its pixel
 * (in the view) and the direction of the ray through it.
 */
float3 froxelPoint(uint3 id, float3 offset, out float2 pixel, out float3 dir)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    float2 pixels = float2(push.pixels & 0xffff, push.pixels >> 16);
    pixel = (float2(id.xy) + offset.xy) / float2(size) * pixels;
    float2 ndc = pixel / pixels * 2 - 1;
    float4 world = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    dir = normalize(world.xyz / world.w);
    float start = id.z == 0 ? 0 : sliceEnd(id.z - 1);
    return dir * lerp(start, sliceEnd(id.z), offset.z);
}

/**
 * Sample `k` of froxel `id` this frame: its centre for one sample without history; otherwise the 3-dimensional R2
 * sequence (Roberts 2018) from the frame and sample, shifted per froxel by a hash (so neighbours sample apart), within
 * it: over frames each froxel covers its volume evenly, its shadows' edges inside it as well as its media's.
 */
float3 jitter(uint3 id, uint k)
{
    if (push.samples <= 1 && push.history <= 0)
    {
        return 0.5;
    }
    uint n = push.frame * max(push.samples, 1) + k;
    uint h = (id.x * 73856093u) ^ (id.y * 19349663u) ^ (id.z * 83492791u);
    h = (h ^ (h >> 16)) * 0x45d9f3bu;
    h = (h ^ (h >> 16)) * 0x45d9f3bu;
    h ^= h >> 16;
    float3 shift = float3(h & 1023u, (h >> 10) & 1023u, (h >> 20) & 1023u) / 1024.0;
    return frac(shift + float(n % 4096u) * float3(0.8191725134, 0.6710436067, 0.5497004779));
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
 * Each froxel's media and the light they scatter towards the camera, per metre: at `samples` points within it (one:
 * its centre, or with history a point jittered within it each frame), the fog's extinction and scattering, lit by
 * every light of the light list reaching it (after its shadow: the sun's cascades cut light shafts) through the
 * Henyey-Greenstein phase of the fog's anisotropy, and by the sky's ambient light; their mean.
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
    AtmosphereFogParameters* fog = fogParameters();
    float g = fog[0].anisotropy;
    uint samples = max(push.samples, 1);
    float4 sum = 0;
    for (uint k = 0; k < samples; k++)
    {
        float2 pixel;
        float3 dir;
        float3 p = froxelPoint(id, jitter(id, k), pixel, dir);
        float3 scattering;
        float kept;
        float sigma = fogMediumAt(fog, p, push.cameraHeight, scattering, kept);
        float3 s = 0;
        if (sigma > 0)
        {
            // The sky's light dimmed indoors (zones' light); the lights by their shadows
            float3 light = push.ambient[0].rgb * kept;
            if (push.lit != 0)
            {
                LightListHeader header = loadHeader(push.lights);
                float depth = mul(push.view[0].viewProjection, float4(p, 1)).w;
                uint2 at = uint2(pixel);
                ClusterLightList list = ClusterLightList(header, at, depth);
                for (uint i = 0; i < list.count(); i++)
                {
                    float3 toLight;
                    // No normal: a point in the air takes no surface's normal offset or receiver plane in its shadow
                    float3 e = lightIrradiance(header, list.light(i), p, float3(0), at, depth, toLight);
                    light += e * fogPhase(dot(toLight, dir), g);
                }
            }
            else
            {
                float3 sun = fog[0].sunDirection;
                float l = length(sun);
                if (l > 0)
                {
                    light += fog[0].sunIlluminance * fogPhase(dot(sun / l, dir), g) * kept; // unshadowed: as the hook
                }
            }
            s = scattering * light;
        }
        sum += float4(s, sigma);
    }
    volumeImages[push.media][id] = sum / float(samples);
}

/**
 * Each froxel's media and light kept over frames (temporal accumulation, as TAA's): last frame's at the froxel's centre
 * reprojected (that frame's grid, through its view-projection and slices; trilinear), bounded by this frame's froxel
 * and its six neighbours (what its jittered samples could have seen: a history from where something moved since is
 * cut back), and `history` of it kept, the rest this frame's. Where the centre was outside last frame's grid, or
 * there was none, this frame's alone.
 */
[shader("compute")]
[numthreads(4, 4, 4)]
void temporalMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size) || id.z >= push.slices)
    {
        return;
    }
    float4 now = volumeImages[push.media][id];
    float4 result = now;
    AtmosphereFogParameters* fog = fogParameters();
    if (push.history > 0 && fog[0].froxelLastCamera.w > 0)
    {
        float2 pixel;
        float3 dir;
        float4 p = float4(froxelPoint(id, 0.5, pixel, dir), 1);
        float w = dot(fog[0].froxelLastW, p);
        float2 uv = float2(dot(fog[0].froxelLastX, p), dot(fog[0].froxelLastY, p)) / max(w, 1e-6) * 0.5 + 0.5;
        float c = sliceCoordinate(length(p.xyz + fog[0].froxelLastCamera.xyz));
        if (w > 0 && all(uv >= 0) && all(uv <= 1) && c <= float(push.slices))
        {
            float4 lo = now;
            float4 hi = now;
            int3 last = int3(int(size.x) - 1, int(size.y) - 1, int(push.slices) - 1);
            int3 offsets[6] = { int3(1, 0, 0), int3(-1, 0, 0), int3(0, 1, 0), int3(0, -1, 0), int3(0, 0, 1),
                int3(0, 0, -1) };
            for (uint i = 0; i < 6; i++)
            {
                float4 v = volumeImages[push.media][uint3(clamp(int3(id) + offsets[i], 0, last))];
                lo = min(lo, v);
                hi = max(hi, v);
            }
            float4 before = volumes[NonUniformResourceIndex(push.last)].SampleLevel(samplers[LINEAR_CLAMP],
                float3(uv, c / float(push.slices)), 0);
            result = lerp(now, clamp(before, lo, hi), push.history);
        }
    }
    volumeImages[push.integrated][id] = result;
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
