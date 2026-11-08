// The cheap tier of the sky (AtmosphereSky's gradient kind): two colours by height, no atmosphere tables.
import atmosphere;
import meshlets.common;

struct GradientPush
{
    View* view;
    AtmosphereFrame* atmosphere; // the sun's direction, illuminance and disc
    uint color;           // storage slot: the scene's color
    uint depth;           // sampled slot: the scene's depth
    uint size;            // the render region: width, height in the high 16 bits
    uint discs;           // bit 0: the sun's disc
    float3 zenithColor;   // radiance straight up, by day
    float pad0;
    float3 horizonColor;  // and at the horizon
    float pad1;
    float3 nightColor;    // and everywhere at night
    float pad2;
};

[[vk::push_constant]]
ConstantBuffer<GradientPush> push;

/** The unit direction (world) through pixel position `pixel` (in pixels, of the render region). */
float3 pixelDirection(float2 pixel, float2 size)
{
    float2 ndc = pixel / size * 2 - 1;
    float4 world = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    return normalize(world.xyz / world.w);
}

/**
 * The cheap tier (the gradient kind): two colours by height, the horizon's towards the zenith's, dimmed by the sun's
 * height towards the night's colour; the sun's disc on it. No atmosphere tables.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void gradientMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    if (textures[push.depth].Load(int3(id.xy, 0)).r > 0)
    {
        return;
    }
    AtmosphereFrame* a = push.atmosphere;
    float3 d = pixelDirection(float2(id.xy) + 0.5, float2(size));
    float up = d.y;
    float sunUp = a[0].lightDirection0.y;
    float day = smoothstep(-0.1, 0.15, sunUp);
    float3 sky = lerp(push.horizonColor, push.zenithColor, sqrt(saturate(up)));
    float3 c = lerp(push.nightColor, sky, day);
    if ((push.discs & 1) != 0 && up > 0)
    {
        c += a[0].lightIlluminance0 * discRadiance(dot(d, a[0].lightDirection0), a[0].lightRadius0, a[0].lightDisc0)
            * day;
    }
    c = min(c, 60000);
    c = skyThroughMedia(HookSite.at(push.view[0].hooks, id.xy, SHADING_SKY), c, d);
    images[push.color][id.xy] = float4(c, 1);
}
