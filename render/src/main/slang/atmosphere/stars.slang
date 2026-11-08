// The stars of the night sky splatted into a view's star buffer (AtmosphereSky; sky.slang reads it): the star field
// of atmosphere-sky (catalogue or procedural: StarField's directions, illuminance and colours) through the frame's
// rotation from the equatorial frame of date into the world.
import atmosphere;
import meshlets.common;

struct StarsPush
{
    View* view;
    uint64_t stars;       // the star buffer, as SkyPush's
    uint64_t field;       // the stars: 8 floats each (equatorial direction of date, lux, linear colour, pad)
    uint count;
    uint size;            // the render region: width | height << 16
    float3 equatorialX;   // equatorial of date -> world: rows
    float pad0;
    float3 equatorialY;
    float pad1;
    float3 equatorialZ;
    float pad2;
};

[[vk::push_constant]]
ConstantBuffer<StarsPush> stars;

/**
 * The stars splatted into the star buffer: each a point (its illuminance in lux times its colour, of luminance 1)
 * shared between the 2 x 2 pixels around where it falls by their bilinear weights, so its light is kept whatever
 * the resolution; added in fixed point (picolux) by atomics. The sky pass turns each pixel's illuminance into radiance
 * by its solid angle, through the atmosphere.
 */
[shader("compute")]
[numthreads(64, 1, 1)]
void starsMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= stars.count)
    {
        return;
    }
    float* f = (float*) (stars.field + (uint64_t) id.x * 32);
    float3 equatorial = float3(f[0], f[1], f[2]);
    float lux = f[3];
    float3 color = float3(f[4], f[5], f[6]);
    float3 d = float3(dot(stars.equatorialX, equatorial), dot(stars.equatorialY, equatorial),
        dot(stars.equatorialZ, equatorial));
    float4 clip = mul(stars.view[0].viewProjection, float4(d, 0));
    if (clip.w <= 0)
    {
        return;
    }
    uint2 size = uint2(stars.size & 0xffff, stars.size >> 16);
    float2 pixel = (clip.xy / clip.w * 0.5 + 0.5) * float2(size) - 0.5;
    int2 base = int2(floor(pixel));
    float2 t = pixel - float2(base);
    uint* buffer = (uint*) stars.stars;
    for (uint corner = 0; corner < 4; corner++)
    {
        int2 p = base + int2(corner & 1, corner >> 1);
        if (any(p < 0) || any(p >= int2(size)))
        {
            continue;
        }
        float w = ((corner & 1) != 0 ? t.x : 1 - t.x) * ((corner >> 1) != 0 ? t.y : 1 - t.y);
        uint at = (uint(p.y) * size.x + uint(p.x)) * 3;
        float3 e = lux * color * w * STAR_UNITS_PER_LUX;
        InterlockedAdd(buffer[at], uint(e.r + 0.5));
        InterlockedAdd(buffer[at + 1], uint(e.g + 0.5));
        InterlockedAdd(buffer[at + 2], uint(e.b + 0.5));
    }
}
