// The atmosphere's look-up tables (Hillaire 2020; atmosphere.slang): the transmittance and multiple-scattering tables,
// made when the atmosphere's media change (engine-side: AtmosphereLuts), and per frame and camera position the
// sky-view table, the aerial perspective volume and the sky capture's panorama (SkyViewLut, SkyCapture).
import atmosphere;
import bindless;

struct LutPush
{
    AtmosphereFrame* atmosphere;
    uint target;      // storage slot written
    uint target2;     // a second one (the aerial perspective's transmittance)
    uint size;        // width | height << 16
    uint depth;       // slices of a volume
    uint sky;         // sampled slot: the sky-view table (the capture)
    uint perPixel;    // 1: integrate every texel's ray (the capture from space), 0: read the sky-view table
    uint skySize;     // the sky-view table's width | height << 16
    float far;        // the aerial perspective's range, km
    float minSteps;
    float maxSteps;
    float stepsPerKm;
    uint pad;
};

[[vk::push_constant]]
ConstantBuffer<LutPush> push;

/** The table's size from its push constant. */
uint2 sizeOf(uint packed)
{
    return uint2(packed & 0xffff, packed >> 16);
}

/** The march the push constants ask for. */
March pushedMarch()
{
    March m = defaultMarch();
    m.minSteps = push.minSteps;
    m.maxSteps = push.maxSteps;
    m.stepsPerKm = push.stepsPerKm;
    return m;
}

/** The camera, clamped above the ground: the tables are made from just above it. */
float3 viewPosition(AtmosphereFrame* a)
{
    float3 c = cameraPosition(a);
    float r = length(c);
    float least = a[0].bottomRadius + 0.0005;
    return r < least ? c / max(r, 1e-6) * least : c;
}

/**
 * The transmittance table (Sec. 5.1, the paper's 256 x 64): from each (radius, zenith cosine), the transmittance to
 * the top of the atmosphere, its optical depth integrated in 40 steps (the paper's count).
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void transmittanceMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = sizeOf(push.size);
    if (any(id.xy >= size))
    {
        return;
    }
    AtmosphereFrame* a = push.atmosphere;
    float2 rMu = transmittanceRMu(a, (float2(id.xy) + 0.5) / float2(size), float2(size));
    float r = rMu.x;
    float mu = rMu.y;
    float3 origin = float3(0, r, 0);
    float3 direction = float3(sqrt(max(1 - mu * mu, 0)), mu, 0);
    float rayLength = max(raySphere(origin, direction, a[0].topRadius), 0);
    const float STEPS = 40;
    float dt = rayLength / STEPS;
    float3 depth = 0;
    for (float i = 0; i < STEPS; i += 1)
    {
        float3 p = origin + direction * ((i + 0.5) * dt);
        Medium m = medium(a, max(sqrt(dot(p, p)) - a[0].bottomRadius, 0));
        depth += m.extinction * dt;
    }
    images[push.target][id.xy] = float4(exp(-depth), 1);
}

groupshared float3 secondOrder[MULTIPLE_SCATTERING_DIRECTIONS];
groupshared float3 transferred[MULTIPLE_SCATTERING_DIRECTIONS];

/**
 * The multiple-scattering table (Sec. 5.5, the paper's 32 x 32): per height and light zenith cosine, a workgroup of
 * 64 directions over the sphere (a spherical Fibonacci set) integrates the second-order light L_2 and the transfer
 * f_ms with the uniform phase function, the ground included; their means give Psi_ms = L_2 / (1 - f_ms), the sum of
 * every scattering order as a geometric series (Eq. 9, 10).
 */
[shader("compute")]
[numthreads(MULTIPLE_SCATTERING_DIRECTIONS, 1, 1)]
void multipleScatteringMain(uint3 group : SV_GroupID, uint index : SV_GroupIndex)
{
    uint2 size = sizeOf(push.size);
    AtmosphereFrame* a = push.atmosphere;
    float2 uv = (float2(group.xy) + 0.5) / float2(size);
    float mu = texelToUnit(uv.x, float(size.x)) * 2 - 1;
    float h = max(texelToUnit(uv.y, float(size.y)), 0) * (a[0].topRadius - a[0].bottomRadius);
    float3 origin = float3(0, a[0].bottomRadius + max(h, 0.0005), 0);
    float3 light = float3(sqrt(max(1 - mu * mu, 0)), mu, 0);
    // Spherical Fibonacci directions: even over the sphere
    float n = float(MULTIPLE_SCATTERING_DIRECTIONS);
    float z = 1 - (2 * float(index) + 1) / n;
    float phi = float(index) * 2.39996323;
    float s = sqrt(max(1 - z * z, 0));
    float3 direction = float3(s * cos(phi), z, s * sin(phi));
    March m = defaultMarch();
    m.minSteps = 64;
    m.maxSteps = 64;
    m.isotropic = true;
    Scattered got = integrate(a, origin, direction, 1e9, m, light);
    secondOrder[index] = got.radiance;
    transferred[index] = got.transmittance;
    GroupMemoryBarrierWithGroupSync();
    for (uint stride = MULTIPLE_SCATTERING_DIRECTIONS / 2; stride > 0; stride /= 2)
    {
        if (index < stride)
        {
            secondOrder[index] += secondOrder[index + stride];
            transferred[index] += transferred[index + stride];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    if (index == 0)
    {
        // The integrals over the sphere with the uniform phase: their means
        float3 l2 = secondOrder[0] / n;
        float3 fms = transferred[0] / n * (4 * ATMOSPHERE_PI) * uniformPhase();
        images[push.target][group.xy] = float4(l2 / max(1 - fms, 1e-4), 1);
    }
}

/**
 * The sky-view table (Sec. 5.3, the paper's 192 x 108): the sky's radiance all around the camera, every texel's ray
 * integrated through the atmosphere to the ground or space, the lit ground included.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void skyViewMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = sizeOf(push.size);
    if (any(id.xy >= size))
    {
        return;
    }
    AtmosphereFrame* a = push.atmosphere;
    float3 camera = viewPosition(a);
    SkyFrame frame = skyFrame(a, camera);
    float2 uv = float2((float(id.x) + 0.5) / float(size.x), texelToUnit((float(id.y) + 0.5) / float(size.y),
        float(size.y)));
    float3 direction = skyViewDirection(frame, uv);
    Scattered got = integrate(a, camera, direction, 1e9, pushedMarch(), float3(0));
    images[push.target][id.xy] = float4(got.radiance, 1);
}

/**
 * The aerial perspective volume: around the camera, by the sky-view table's directions (u, v) and distance (slices,
 * squared towards the camera: slice i ends at far * ((i + 1) / slices)^2), the light scattered in and the
 * transmittance from the camera to there. One thread per direction marches its slices front to back, two steps a
 * slice. Hillaire 2020 lays its 32^3 froxels over the view's frustum; laid around the camera instead, one volume
 * serves every view and eye at the camera's position and every point shaded, on screen or off (reflections, probes).
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void aerialMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = sizeOf(push.size);
    if (any(id.xy >= size))
    {
        return;
    }
    AtmosphereFrame* a = push.atmosphere;
    float3 camera = viewPosition(a);
    SkyFrame frame = skyFrame(a, camera);
    float2 uv = (float2(id.xy) + 0.5) / float2(size);
    float3 direction = skyViewDirection(frame, uv);
    // The part of the ray inside the atmosphere
    float r = length(camera);
    float enter = 0;
    if (r > a[0].topRadius)
    {
        enter = raySphere(camera, direction, a[0].topRadius);
        enter = enter < 0 ? 1e30 : enter;
    }
    // The ground does not end the rays: what ends them is the scene's geometry (terrain above the planet's sphere,
    // objects in the air), at whatever depth it is; below the ground the media keep the ground's density
    float toTop = raySphere(camera + direction * min(enter, 1e6), direction, a[0].topRadius + 1e-3);
    float leave = enter < 1e29 ? enter + max(toTop, 0) : 0;
    float3 radiance = 0;
    float3 throughput = 1;
    float before = 0;
    for (uint slice = 0; slice < push.depth; slice++)
    {
        float w = float(slice + 1) / float(push.depth);
        float d = push.far * w * w;
        float t0 = clamp(before, enter, leave);
        float t1 = clamp(d, enter, leave);
        segment(a, camera, direction, t0, t1, 2, radiance, throughput);
        volumeImages[push.target][uint3(id.xy, slice)] = float4(radiance, 1);
        volumeImages[push.target2][uint3(id.xy, slice)] = float4(throughput, 1);
        before = d;
    }
}

/**
 * The sky capture: the sky around the camera as an equirectangular panorama in the engine's convention
 * (environment/filter.slang's panoramaUv: u = 0.5 + atan2(z, x) / 2 pi, v from +y down to -y), without the discs of the sun
 * and the moon (they light the scene as its directional lights) and without stars; with airglow and the Milky Way
 * through the atmosphere. Read from the sky-view table, or integrated per texel from space.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void captureMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = sizeOf(push.size);
    if (any(id.xy >= size))
    {
        return;
    }
    AtmosphereFrame* a = push.atmosphere;
    float3 camera = viewPosition(a);
    float2 uv = (float2(id.xy) + 0.5) / float2(size);
    float phi = (uv.x - 0.5) * 2 * ATMOSPHERE_PI; // filter.slang's panoramaUv: +x at u = 0.5
    float theta = uv.y * ATMOSPHERE_PI;
    float3 direction = float3(cos(phi) * sin(theta), cos(theta), sin(phi) * sin(theta));
    float3 c;
    if (push.perPixel != 0)
    {
        c = integrate(a, camera, direction, 1e9, pushedMarch(), float3(0)).radiance;
    }
    else
    {
        SkyFrame frame = skyFrame(a, camera);
        float2 sky = skyViewTexel(skyViewUv(frame, direction), float2(sizeOf(push.skySize)));
        c = textures[push.sky].SampleLevel(samplers[LINEAR_REPEAT], sky, 0).rgb;
    }
    c += nightLight(a, camera, direction) * transmittanceToSpace(a, camera, direction);
    images[push.target][id.xy] = float4(c, 1);
}
