// Aerial perspective as a media shading hook (engine-render's hooks.slang): the atmosphere between the camera and
// every shaded point (opaque pixels, glass, particles, reflections' and probes' hits), read from the aerial perspective
// volume (luts.slang's aerialMain, made each frame around the camera: AerialPerspective in Java) with one trilinear
// look-up each of its in-scattered light and transmittance: radiance * T + L (Hillaire 2020 Sec. 5.4). The sky itself
// is the sky pass's (its rays hold the whole atmosphere): this hook leaves the sky's points alone.
module aerial;

import hooks;
import bindless;
import atmosphere;

/**
 * What the hook reads, written each frame by AerialPerspective: the volume's frame around the camera (its zenith and
 * horizontal axes, the horizon's depression), its range and slices, and its two images' sampled slots. No pointers
 * (pitfalls.md: a shader is never handed an address it may load through while not running the hook).
 */
[ShadingHookParameters]
public struct AerialPerspectiveParameters
{
    public float3 up = float3(0, 1, 0);
    public float beta = 0;            // the horizon's depression at the camera, radians
    public float3 north = float3(0, 0, -1);
    public float far = 100;           // km: the volume's last slice
    public float3 east = float3(1, 0, 0);
    public uint slices = 32;
    public uint scattered = 0;        // sampled slot: in-scattered light (rgb)
    public uint transmitted = 0;      // sampled slot: transmittance (rgb)
    public uint width = 64;           // the volume's directions: width x height
    public uint height = 32;
};

[ShadingHook("atmosphere.aerialPerspective")]
[ShadingHookBefore("atmosphere.fog")]
public struct AerialPerspective : IMediaHook
{
    public typealias Parameters = AerialPerspectiveParameters;

    public static void media(ShadingPoint p, AerialPerspectiveParameters* ap, inout ViewRay ray)
    {
        if (p.path == SHADING_SKY)
        {
            return;
        }
        float metres = length(p.position);
        if (!(metres > 0))
        {
            return;
        }
        SkyFrame frame;
        frame.up = ap[0].up;
        frame.north = ap[0].north;
        frame.east = ap[0].east;
        frame.beta = ap[0].beta;
        float2 uv = skyViewUv(frame, p.position / metres);
        float slices = float(ap[0].slices);
        float height = float(ap[0].height);
        uv.y = clamp(uv.y, 0.5 / height, 1 - 0.5 / height);
        // Slice i ends at far ((i + 1) / slices)^2: the continuous slice of this distance
        float s = sqrt(min(metres * 0.001 / ap[0].far, 1)) * slices - 1;
        float weight = 1;
        if (s < 0)
        {
            weight = s + 1; // before the first slice's end: towards nothing at the camera
            s = 0;
        }
        float3 at = float3(uv, (s + 0.5) / slices);
        float3 light = volumes[NonUniformResourceIndex(ap[0].scattered)].SampleLevel(samplers[LINEAR_REPEAT], at, 0)
            .rgb;
        float3 through = volumes[NonUniformResourceIndex(ap[0].transmitted)].SampleLevel(samplers[LINEAR_REPEAT], at,
            0).rgb;
        ray.through(lerp(float3(1), through, weight), light * weight);
    }
};
