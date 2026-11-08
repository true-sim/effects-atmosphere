// Fog as a media shading hook (engine-render's hooks.slang): outdoor height fog, fog volumes and indoor zones, all
// integrated along the ray from the camera to each shaded point (effects-atmosphere#4).
//
// The outdoor fog is exponential height fog: extinction density * exp(-heightFalloff * (height - baseHeight)) per
// metre, scattering `albedo` of it. Regions are boxes or ellipsoids the technique (AtmosphereFog) writes each frame from
// the scene's components, camera-relative, nearest first:
//   - a fog volume adds a medium of its own (density at its centre's height, falling off upwards by its falloff, its
//     albedo), faded in over its blend distance from its surface;
//   - an indoor zone takes the outdoor fog out (EXCLUDE) or replaces it by a medium of its own (REPLACE), and dims the
//     outdoor light reaching the media inside it to its `light`.
// So the air outside a window is fogged up to the glass and the room behind stays clear, whatever is shaded: the hook
// does not depend on which object a point belongs to, only on where the ray between it and the camera runs.
//
// The ray is cut at every region boundary it crosses (at most regionsPerRay regions per ray, the nearest). Pieces
// crossing no region hold height fog alone and are integrated in closed form (optical depth and in-scattering exact
// for its constant albedo and light); pieces inside regions are integrated in `steps` steps, each a homogeneous slab
// (transmittance exp(-sigma dt), in-scattering (1 - exp(-sigma dt)) / sigma times the scattered light: Hillaire 2015,
// "Physically based and unified volumetric rendering"), exact for hard-edged homogeneous regions.
//
// The light the media scatter is the same everywhere along a ray: `ambient` (isotropic radiance, the sky's light) plus
// the sun's illuminance through a Henyey-Greenstein phase function of the angle between the ray and the sun. The sun's
// shadows are not in it (no shafts); indoor zones stand in for them where the outdoor light does not reach.
module fog;

import hooks;
import bindless;

/** A region's shape: a box of half extents `half` along its axes. */
public static const uint FOG_BOX = 0;
/** An ellipsoid of radii `half` along its axes. */
public static const uint FOG_ELLIPSOID = 1;
/** A region's role (kind >> 4): a fog volume, adding its medium. */
public static const uint FOG_VOLUME = 0;
/** An indoor zone taking the outdoor fog out. */
public static const uint FOG_EXCLUDE = 1;
/** An indoor zone replacing the outdoor fog by its own medium. */
public static const uint FOG_REPLACE = 2;
/** The bytes of a region in the technique's buffer: 6 float4s (FogRegion below). */
public static const uint FOG_REGION_BYTES = 96;
/** Regions a ray takes at most (FogSettings.REGIONS_PER_RAY's maximum). */
public static const uint FOG_MAX_HITS = 16;

/** The fog's parameters: what content sets (engine-scene's ShadingHookComponent) and what the technique adds. */
[ShadingHookParameters]
public struct AtmosphereFogParameters
{
    public float3 albedo = float3(0.9, 0.9, 0.9);  // the outdoor fog's single-scattering albedo
    public float density = 0;                      // the outdoor fog's extinction per metre at baseHeight (0: none)
    public float3 ambient = float3(0.5, 0.55, 0.6); // isotropic radiance the media scatter (the sky's light)
    public float heightFalloff = 0.05;             // per metre of height (0: uniform)
    public float3 sunDirection = float3(0, 1, 0);  // towards the sun
    public float baseHeight = 0;                   // metres
    public float3 sunIlluminance = float3(0, 0, 0); // the sun's light the media scatter (0: none)
    public float anisotropy = 0.6;                 // Henyey-Greenstein g of the sun's light: forward scattering
    public float skyDistance = 20000;              // metres of media along a ray to the sky
    public uint regionCount = 0;                   // the technique's regions this frame
    public uint regionsPerRay = 8;                 // FogSettings.REGIONS_PER_RAY
    public uint steps = 8;                         // FogSettings.STEPS: steps per piece of a ray inside regions
    // Froxels (a FogFroxels technique: atmosphere-render's VolumetricFog): the fog integrated in the view's frustum
    // with every light's shadows, up to froxelFar along each ray; the hook continues from there as above
    public uint froxels = 0;                       // sampled slot: in-scattered light (rgb) and transmittance (a)
    public uint64_t regions;                       // the technique's buffer: regionCount FogRegions
    public float froxelFar = 0;                    // metres along a ray they reach (0: no froxels)
    public float froxelNear = 0.5;                 // metres: their first slice's end (slices exponential after it)
    public uint froxelSlices = 64;
    public uint pad0 = 0;
    public float4 froxelX = float4(0, 0, 0, 0);    // clip x of a camera-relative point (x, y, z, 1): the grid's
    public float4 froxelY = float4(0, 0, 0, 0);    // view-projection, by rows (x, y and w)
    public float4 froxelW = float4(0, 0, 0, 0);
};

/** A region as the technique writes it (FogRegions, AtmosphereFog), read a field at a time. */
struct FogRegion
{
    float3 center;     // camera-relative
    uint kind;         // shape | role << 4
    float3 axisX;      // unit axes, world
    float density;
    float3 axisY;
    float falloff;
    float3 axisZ;
    float blend;       // metres from its surface over which it fades in
    float3 half;       // half extents (radii) along its axes, metres
    float light;       // zones: the outdoor light reaching inside (0 to 1)
    float3 albedo;
};

FogRegion fogRegion(uint64_t regions, uint index)
{
    float* f = (float*) (regions + (uint64_t) index * FOG_REGION_BYTES);
    FogRegion r;
    r.center = float3(f[0], f[1], f[2]);
    r.kind = asuint(f[3]);
    r.axisX = float3(f[4], f[5], f[6]);
    r.density = f[7];
    r.axisY = float3(f[8], f[9], f[10]);
    r.falloff = f[11];
    r.axisZ = float3(f[12], f[13], f[14]);
    r.blend = f[15];
    r.half = float3(f[16], f[17], f[18]);
    r.light = f[19];
    r.albedo = float3(f[20], f[21], f[22]);
    return r;
}

/** Where the ray from the camera along `dir` runs inside `r`, within [0, dist]: [t0, t1]; false if nowhere. */
bool fogHit(FogRegion r, float3 dir, float dist, out float t0, out float t1)
{
    float3 o = -r.center;
    float3 ol = float3(dot(r.axisX, o), dot(r.axisY, o), dot(r.axisZ, o)) / r.half;
    float3 dl = float3(dot(r.axisX, dir), dot(r.axisY, dir), dot(r.axisZ, dir)) / r.half;
    t0 = 0;
    t1 = 0;
    if ((r.kind & 15) == FOG_ELLIPSOID)
    {
        float a = dot(dl, dl);
        float b = dot(ol, dl);
        float c = dot(ol, ol) - 1;
        float disc = b * b - a * c;
        if (disc < 0 || a <= 0)
        {
            return false;
        }
        float s = sqrt(disc);
        t0 = (-b - s) / a;
        t1 = (-b + s) / a;
    }
    else
    {
        float3 safe = select(abs(dl) < 1e-12, float3(1e-12), dl);
        float3 inv = 1 / safe;
        float3 ta = (-1 - ol) * inv;
        float3 tb = (1 - ol) * inv;
        float3 lo = min(ta, tb);
        float3 hi = max(ta, tb);
        t0 = max(max(lo.x, lo.y), lo.z);
        t1 = min(min(hi.x, hi.y), hi.z);
    }
    t0 = max(t0, 0);
    t1 = min(t1, dist);
    return t1 > t0;
}

/** How far inside `r` the camera-relative point `x` is, 0 to 1: 1 deeper than its blend distance. */
float fogWeight(FogRegion r, float3 x)
{
    float3 d = x - r.center;
    float3 l = float3(dot(r.axisX, d), dot(r.axisY, d), dot(r.axisZ, d));
    float inside;
    if ((r.kind & 15) == FOG_ELLIPSOID)
    {
        inside = (1 - length(l / r.half)) * min(min(r.half.x, r.half.y), r.half.z);
    }
    else
    {
        float3 e = r.half - abs(l);
        inside = min(min(e.x, e.y), e.z);
    }
    return r.blend > 0 ? saturate(inside / r.blend) : (inside >= 0 ? 1.0 : 0.0);
}

/**
 * What region `r` adds at camera-relative point `x` (atmosphere-fog's media, one region): a fog volume its extinction
 * (`sigma`) and scattering (`own`, before the light reaching it); an indoor zone takes the outdoor fog out
 * (`outdoor`), dims the outdoor light (`lightKept`) and, replacing, adds its own lit scattering (`zones`).
 */
void fogRegionMedium(FogRegion r, float3 x, inout float sigma, inout float3 own, inout float3 zones,
    inout float outdoor, inout float lightKept, bool shadowed)
{
    float w = fogWeight(r, x);
    if (w <= 0)
    {
        return;
    }
    uint role = r.kind >> 4;
    if (role == FOG_VOLUME)
    {
        float rise = clamp(-r.falloff * (x.y - r.center.y), -80.0, 80.0);
        float sv = r.density * exp(rise) * w;
        sigma += sv;
        own += sv * r.albedo;
    }
    else
    {
        outdoor = min(outdoor, 1 - w);
        lightKept = min(lightKept, lerp(1, r.light, w));
        if (role == FOG_REPLACE)
        {
            float sz = r.density * w;
            sigma += sz;
            zones += sz * r.albedo * (shadowed ? 1.0 : r.light);
        }
    }
}

/**
 * The fog's media at camera-relative point `x`, every region of the frame counted (the froxels' injection): its
 * extinction per metre, and its scattering per metre (rgb, albedo included) that multiplies the light reaching the
 * point, and in `kept` how much of the outdoor ambient light reaches it (indoor zones' `light`). Lights whose shadows
 * the caller takes (the froxels: shadow maps decide where the sun reaches) are not dimmed by the zones; the ambient
 * light is, by `kept`. `cameraHeight` is the camera's height over the fog's base height.
 */
public float fogMediumAt(AtmosphereFogParameters* fog, float3 x, float cameraHeight, out float3 scattering,
    out float kept)
{
    float sigma = 0;
    float3 own = 0;
    float3 zones = 0;
    float outdoor = 1;
    float lightKept = 1;
    uint64_t regions = fog[0].regions;
    uint count = fog[0].regionCount;
    if (regions != 0)
    {
        for (uint i = 0; i < count; i++)
        {
            fogRegionMedium(fogRegion(regions, i), x, sigma, own, zones, outdoor, lightKept, true);
        }
    }
    float so = fog[0].density * exp(clamp(-fog[0].heightFalloff * (cameraHeight + x.y), -80.0, 80.0)) * outdoor;
    sigma += so;
    scattering = so * fog[0].albedo + own + zones;
    kept = lightKept;
    return sigma;
}

/**
 * The froxels' integral from the camera to `distance` metres along the ray through `uv` (0 to 1 over the view):
 * in-scattered light (rgb) and transmittance (a). Slice i ends at froxelNear * (froxelFar / froxelNear)^(i / (slices
 * - 1)); before the first slice's end, towards nothing at the camera.
 */
public float4 froxelsAt(AtmosphereFogParameters* fog, float2 uv, float distance)
{
    float slices = float(fog[0].froxelSlices);
    float near = fog[0].froxelNear;
    float j = distance < near ? distance / near : 1 + log(distance / near) / log(fog[0].froxelFar / near)
        * (slices - 1);
    float weight = saturate(j);
    float s = max(j - 1, 0);
    float4 f = volumes[NonUniformResourceIndex(fog[0].froxels)].SampleLevel(samplers[LINEAR_CLAMP],
        float3(uv, (s + 0.5) / slices), 0);
    return float4(f.rgb * weight, lerp(1, f.a, weight));
}

/** The Henyey-Greenstein phase function, per steradian. */
public float fogPhase(float cosTheta, float g)
{
    float d = 1 + g * g - 2 * g * cosTheta;
    return (1 - g * g) / (4 * 3.14159265 * d * sqrt(max(d, 1e-6)));
}

/** (1 - exp(-x)) / x, also for x near 0. */
float fogShare(float x)
{
    return abs(x) > 1e-4 ? (1 - exp(-x)) / x : 1 - 0.5 * x;
}

[ShadingHook("atmosphere.fog")]
public struct AtmosphereFog : IMediaHook
{
    public typealias Parameters = AtmosphereFogParameters;

    public static void media(ShadingPoint p, AtmosphereFogParameters* fog, inout ViewRay ray)
    {
        float3 dir;
        float dist;
        if (p.path == SHADING_SKY)
        {
            dir = -p.toViewer;
            dist = fog[0].skyDistance;
        }
        else
        {
            dist = length(p.position);
            dir = dist > 0 ? p.position / dist : -p.toViewer;
        }
        float3 albedo = fog[0].albedo;
        float density = fog[0].density;
        float falloff = fog[0].heightFalloff;
        float cameraHeight = p.camera.y + p.cameraLow.y - fog[0].baseHeight;
        float3 sun = fog[0].sunDirection;
        float sunLength = length(sun);
        float3 light = fog[0].ambient;
        if (sunLength > 0)
        {
            light += fog[0].sunIlluminance * fogPhase(dot(sun / sunLength, dir), fog[0].anisotropy);
        }

        // The regions the ray crosses: the first regionsPerRay of them (written nearest first)
        uint hitIndex[FOG_MAX_HITS];
        float hitStart[FOG_MAX_HITS];
        float hitEnd[FOG_MAX_HITS];
        for (uint i = 0; i < FOG_MAX_HITS; i++)
        {
            hitIndex[i] = 0;
            hitStart[i] = 0;
            hitEnd[i] = 0;
        }
        uint hits = 0;
        uint count = fog[0].regionCount;
        uint64_t regions = fog[0].regions;
        uint limit = min(fog[0].regionsPerRay, FOG_MAX_HITS);
        if (regions != 0)
        {
            for (uint i = 0; i < count && hits < limit; i++)
            {
                float t0;
                float t1;
                if (fogHit(fogRegion(regions, i), dir, dist, t0, t1))
                {
                    hitIndex[hits] = i;
                    hitStart[hits] = t0;
                    hitEnd[hits] = t1;
                    hits++;
                }
            }
        }

        // The ray's pieces between boundaries, in order
        float cuts[2 * FOG_MAX_HITS + 2];
        for (uint i = 0; i < 2 * FOG_MAX_HITS + 2; i++)
        {
            cuts[i] = 0;
        }
        uint n = 0;
        cuts[n++] = 0;
        cuts[n++] = dist;
        for (uint h = 0; h < hits; h++)
        {
            cuts[n++] = hitStart[h];
            cuts[n++] = hitEnd[h];
        }
        for (uint i = 1; i < n; i++)
        {
            float v = cuts[i];
            int j = int(i) - 1;
            while (j >= 0 && cuts[j] > v)
            {
                cuts[j + 1] = cuts[j];
                j--;
            }
            cuts[j + 1] = v;
        }

        float transmittance = 1;
        float3 scattered = 0;
        // Within the froxels' reach, their integral (every light, its shadows): the pieces go on from its end
        float start = 0;
        if (fog[0].froxelFar > 0)
        {
            float4 at = p.path == SHADING_SKY ? float4(dir, 0) : float4(p.position, 1);
            float cw = dot(fog[0].froxelW, at);
            float2 uv = float2(dot(fog[0].froxelX, at), dot(fog[0].froxelY, at)) / max(cw, 1e-6) * 0.5 + 0.5;
            if (cw > 0 && all(uv >= 0) && all(uv <= 1))
            {
                start = min(dist, fog[0].froxelFar);
                float4 f = froxelsAt(fog, uv, start);
                scattered = f.rgb;
                transmittance = f.a;
            }
        }
        uint steps = max(fog[0].steps, 1u);
        for (uint k = 0; k + 1 < n; k++)
        {
            float a = max(cuts[k], start);
            float b = cuts[k + 1];
            float length = b - a;
            if (length <= 0)
            {
                continue;
            }
            float middle = 0.5 * (a + b);
            bool inside = false;
            for (uint h = 0; h < hits; h++)
            {
                inside = inside || (hitStart[h] <= middle && middle <= hitEnd[h]);
            }
            if (!inside)
            {
                // Height fog alone: closed form
                float rise = falloff * dir.y * length;
                float base = density * exp(clamp(-falloff * (cameraHeight + dir.y * a), -80.0, 80.0));
                float depth = base * length * fogShare(max(rise, -80.0));
                float t = exp(-depth);
                scattered += transmittance * (1 - t) * albedo * light;
                transmittance *= t;
                continue;
            }
            float dt = length / float(steps);
            for (uint s = 0; s < steps; s++)
            {
                float3 x = dir * (a + (float(s) + 0.5) * dt);
                float outdoor = 1;
                float lightKept = 1;
                float sigma = 0;
                float3 own = 0;   // volumes' scattering, before the light reaching them
                float3 zones = 0; // replacing zones' scattering, lit by their own light
                for (uint h = 0; h < hits; h++)
                {
                    fogRegionMedium(fogRegion(regions, hitIndex[h]), x, sigma, own, zones, outdoor, lightKept,
                        false);
                }
                float so = density * exp(clamp(-falloff * (cameraHeight + x.y), -80.0, 80.0)) * outdoor;
                sigma += so;
                float3 emitted = (so * albedo * lightKept + own * lightKept + zones) * light;
                float t = exp(-sigma * dt);
                scattered += transmittance * emitted * (sigma > 1e-9 ? (1 - t) / sigma : dt);
                transmittance *= t;
            }
        }
        ray.through(float3(transmittance), scattered);
    }
};
