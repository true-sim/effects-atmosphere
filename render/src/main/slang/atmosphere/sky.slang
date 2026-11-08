// The sky behind a view's scene (AtmosphereSky): over every pixel nothing covers (reverse depth 0), the atmosphere's
// light along the pixel's direction (the sky-view table, or integrated per pixel: from space, or by choice), the
// discs of the sun (limb darkened) and the moon (lit by the sun, its phase), the stars (splatted by starsMain), airglow
// and the Milky Way, all through the atmosphere's transmittance; then the view's other media hooks as a point at
// infinity (fog regions in front of the sky). A compute pass over the render region, in place on the scene's color.
import atmosphere;
import meshlets.common;

struct AtmosphereSkyPush
{
    View* view;
    AtmosphereFrame* atmosphere;
    uint64_t stars;       // the star buffer: 3 uints a pixel, picolux (STAR_UNITS_PER_LUX); always a valid buffer
    uint color;           // storage slot: the scene's color
    uint depth;           // sampled slot: the scene's depth
    uint size;            // the render region: width, height in the high 16 bits
    uint sky;             // sampled slot: the sky-view table
    uint skySize;         // its width | height << 16
    uint perPixel;        // 1: integrate each pixel's ray instead of reading the table
    uint starsOn;         // 1: add the star buffer
    uint discs;           // bit 0: the sun's disc, bit 1: the moon's
    float sceneUnitsPerLux;
    float minSteps;
    float maxSteps;
    float stepsPerKm;
    float3 moonDirection; // towards the moon (unit, world)
    float moonRadius;     // its angular radius, radians
    float3 moonSun;       // towards the sun as the moon sees it (unit, world)
    float moonRadiance;   // 1/sr: Lommel-Seeliger's factor so the disc gives the moon's illuminance (light 1's)
};

[[vk::push_constant]]
ConstantBuffer<AtmosphereSkyPush> push;

/** The unit direction (world) through pixel position `pixel` (in pixels, of the render region). */
float3 pixelDirection(float2 pixel, float2 size)
{
    float2 ndc = pixel / size * 2 - 1;
    float4 world = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    return normalize(world.xyz / world.w);
}

/** The solid angle (sr) pixel `id` covers. */
float pixelSolidAngle(uint2 id, float2 size)
{
    float3 d00 = pixelDirection(float2(id), size);
    float3 d10 = pixelDirection(float2(id) + float2(1, 0), size);
    float3 d01 = pixelDirection(float2(id) + float2(0, 1), size);
    return max(length(cross(d10 - d00, d01 - d00)), 1e-12);
}

/** The moon's disc along `d`: a sphere lit by the sun with Lommel-Seeliger's law, cos i / (cos i + cos e). */
float moonDisc(float3 d)
{
    float cosAngle = dot(d, push.moonDirection);
    float sinRadius = sin(push.moonRadius);
    float3 across = d - push.moonDirection * cosAngle;
    float x = length(across) / sinRadius;
    if (cosAngle <= 0 || x >= 1)
    {
        return 0;
    }
    // The sphere's normal at the pixel, facing the viewer
    float3 side = x > 1e-6 ? across / length(across) : float3(0);
    float3 normal = side * x - push.moonDirection * sqrt(max(1 - x * x, 0));
    float cosI = dot(normal, push.moonSun);
    float cosE = -dot(normal, push.moonDirection) ;
    if (cosI <= 0)
    {
        return 0;
    }
    return push.moonRadiance * cosI / max(cosI + cosE, 1e-4);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void skyMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    if (textures[push.depth].Load(int3(id.xy, 0)).r > 0)
    {
        return; // a surface: its aerial perspective is the media hook's
    }
    AtmosphereFrame* a = push.atmosphere;
    float3 d = pixelDirection(float2(id.xy) + 0.5, float2(size));
    float3 camera = cameraPosition(a);
    float r = length(camera);
    float least = a[0].bottomRadius + 0.0005;
    camera = r < least ? camera / max(r, 1e-6) * least : camera;
    float3 c;
    if (push.perPixel != 0 || r > a[0].topRadius)
    {
        March m = defaultMarch();
        m.minSteps = push.minSteps;
        m.maxSteps = push.maxSteps;
        m.stepsPerKm = push.stepsPerKm;
        c = integrate(a, camera, d, 1e9, m, float3(0)).radiance;
    }
    else
    {
        SkyFrame frame = skyFrame(a, camera);
        float2 uv = skyViewTexel(skyViewUv(frame, d), tableSize(push.skySize));
        c = textures[push.sky].SampleLevel(samplers[LINEAR_REPEAT], uv, 0).rgb;
    }
    float3 through = transmittanceToSpace(a, camera, d);
    if (any(through > 0))
    {
        float3 space = nightLight(a, camera, d);
        if ((push.discs & 1) != 0 && a[0].lightCount > 0)
        {
            space += a[0].lightIlluminance0 * discRadiance(dot(d, a[0].lightDirection0), a[0].lightRadius0,
                a[0].lightDisc0);
        }
        if ((push.discs & 2) != 0)
        {
            space += moonDisc(d) * a[0].lightIlluminance1;
        }
        if (push.starsOn != 0)
        {
            uint* stars = (uint*) push.stars;
            uint at = (id.y * size.x + id.x) * 3;
            float3 e = float3(float(stars[at]), float(stars[at + 1]), float(stars[at + 2])) / STAR_UNITS_PER_LUX;
            space += e * push.sceneUnitsPerLux / pixelSolidAngle(id.xy, float2(size));
        }
        c += space * through;
    }
    c = min(c, 60000); // within half floats
    c = skyThroughMedia(HookSite.at(push.view[0].hooks, id.xy, SHADING_SKY), c, d);
    images[push.color][id.xy] = float4(c, 1);
}
