// The atmosphere of a planet as participating media, and the look-up tables of Hillaire 2020 ("A Scalable and
// Production Ready Sky and Atmosphere Rendering Technique", EGSR 2020): what the sky, the sky capture, the aerial
// perspective hook and other libraries (clouds: effects-weather) evaluate. Implemented from the paper; the
// transmittance table's parameterisation is Bruneton's (Bruneton and Neyret 2008, "Precomputed Atmospheric
// Scattering"; Bruneton 2017's rewrite), the sky-view table's latitude mapping the paper's (Sec. 5.3).
//
// Units: kilometres for lengths and 1/km for coefficients inside (precision at planetary scale); the world is in
// metres, converted at the boundary (AtmosphereFrame.camera, the hook's distances). Radiance and illuminance are in
// the scene's units: the lights' illuminance (sun, moon) is given in scene units, so every result is too
// (AtmosphereFrame.lights; Java: Atmosphere.sceneUnits, from lux and the camera's EV100).
//
// Frames: positions are relative to the planet's centre, in the world's axes (y up at the world's origin, which lies
// `originAltitude` above the ground at the planet's surface): planet = world / 1000 + (0, bottomRadius + origin, 0).
// Directions are world directions.
module atmosphere;

import bindless;

public static const float ATMOSPHERE_PI = 3.14159265358979;
/** Lights the sky integrates at most: the sun and the moon. */
public static const uint ATMOSPHERE_MAX_LIGHTS = 2;
/** The transmittance table's default size (the paper's 256 x 64). */
public static const uint TRANSMITTANCE_WIDTH = 256;
public static const uint TRANSMITTANCE_HEIGHT = 64;
/** The multiple-scattering table's default size (the paper's 32 x 32). */
public static const uint MULTIPLE_SCATTERING_SIZE = 32;
/** Directions the multiple-scattering table integrates over the sphere per texel (the paper's 64). */
public static const uint MULTIPLE_SCATTERING_DIRECTIONS = 64;
/** The fixed-point scale of the star buffer: picolux per unit. */
public static const float STAR_UNITS_PER_LUX = 1.0e12;

/**
 * What a frame knows of the atmosphere, written by the host (Java: AtmosphereFrameLayout, Atmosphere.write) into a
 * buffer every frame, read a field at a time. Coefficients in 1/km at density 1.
 */
[HostLayout]
public struct AtmosphereFrame
{
    public float3 rayleighScattering;
    public float bottomRadius;          // km: the ground
    public float3 mieScattering;
    public float topRadius;             // km: the top of the atmosphere
    public float3 mieExtinction;
    public float rayleighScaleHeight;   // km
    public float3 ozoneAbsorption;
    public float mieScaleHeight;        // km
    public float3 groundAlbedo;
    public float miePhaseG;             // Cornette-Shanks asymmetry
    public float ozoneCenter;           // km: the tent profile's peak
    public float ozoneHalfWidth;        // km: its half width
    public uint lightCount;             // lights scattered: 0 to ATMOSPHERE_MAX_LIGHTS
    public uint multipleScattering;     // 1: the multiple-scattering table lights the media, 0: single scattering
    public float3 camera;               // km, from the planet's centre
    public float originAltitude;        // km: the world origin's height above the ground
    public float3 lightDirection0;      // towards the light (unit, world)
    public float lightRadius0;          // its angular radius, radians
    public float3 lightIlluminance0;    // outside the atmosphere, scene units, per channel
    public float lightDisc0;            // 1 / the disc's solid angle, 1/sr (its mean radiance per illuminance)
    public float3 lightDirection1;
    public float lightRadius1;
    public float3 lightIlluminance1;
    public float lightDisc1;
    public uint transmittance;          // sampled slot: the transmittance table
    public uint multiple;               // sampled slot: the multiple-scattering table
    public uint transmittanceSize;      // width | height << 16
    public uint multipleSize;           // width | height << 16
    // The night sky (atmosphere-sky's Airglow and MilkyWay, evaluated as their Java reference expressions)
    public float3 airglowColor;         // luminance-normalised linear colour
    public float airglowZenith;         // its radiance at the zenith above the atmosphere, scene units (0: off)
    public float airglowLayer;          // the emitting layer's height, km
    public float milkyWayCentre;        // the band's radiance towards the galactic centre, scene units (0: off)
    public float milkyWayLatitude;      // its exponential fall-off with |b|, degrees
    public float milkyWayLongitude;     // the bulge's Gaussian fall-off with |l|, degrees
    public float milkyWayDisc;          // the band's brightness far from the centre, relative to the centre
    public float pad0;
    public float pad1;
    public float pad2;
    public float3 galacticX;            // world direction -> galactic frame: its rows
    public float pad3;
    public float3 galacticY;
    public float pad4;
    public float3 galacticZ;
    public float pad5;
    public float3 equatorialX;          // equatorial frame of date -> world: its rows (stars' places)
    public float pad6;
    public float3 equatorialY;
    public float pad7;
    public float3 equatorialZ;
    public float pad8;
};

/** The media at a point: what scatters and what is lost, per km. */
public struct Medium
{
    public float3 scattering;
    public float3 extinction;
    public float3 rayleigh;   // Rayleigh scattering alone
    public float3 mie;        // Mie scattering alone
};

/** The media at height `h` km above the ground (Hillaire 2020 Sec. 4: exponential Rayleigh and Mie, ozone tent). */
public Medium medium(AtmosphereFrame* a, float h)
{
    float rayleighDensity = exp(-max(h, 0) / a[0].rayleighScaleHeight);
    float mieDensity = exp(-max(h, 0) / a[0].mieScaleHeight);
    float ozoneDensity = max(0, 1 - abs(h - a[0].ozoneCenter) / a[0].ozoneHalfWidth);
    Medium m;
    m.rayleigh = a[0].rayleighScattering * rayleighDensity;
    m.mie = a[0].mieScattering * mieDensity;
    m.scattering = m.rayleigh + m.mie;
    m.extinction = m.rayleigh + a[0].mieExtinction * mieDensity + a[0].ozoneAbsorption * ozoneDensity;
    return m;
}

/** Rayleigh's phase function, 3 / (16 pi) (1 + cos^2). */
public float rayleighPhase(float cosTheta)
{
    return 3.0 / (16.0 * ATMOSPHERE_PI) * (1 + cosTheta * cosTheta);
}

/** The Cornette-Shanks phase function (Cornette and Shanks 1992), as Bruneton and Hillaire use for Mie scattering. */
public float cornetteShanksPhase(float g, float cosTheta)
{
    float k = 3.0 / (8.0 * ATMOSPHERE_PI) * (1 - g * g) / (2 + g * g);
    float d = max(1 + g * g - 2 * g * cosTheta, 1e-6);
    return k * (1 + cosTheta * cosTheta) / (d * sqrt(d));
}

/** The isotropic phase function, 1 / (4 pi). */
public float uniformPhase()
{
    return 1.0 / (4.0 * ATMOSPHERE_PI);
}

/**
 * The nearest intersection at t >= 0 of the ray from `origin` along unit `direction` with the sphere of `radius` about
 * the planet's centre, or -1.
 */
public float raySphere(float3 origin, float3 direction, float radius)
{
    float b = dot(origin, direction);
    float c = dot(origin, origin) - radius * radius;
    float d = b * b - c;
    if (d < 0)
    {
        return -1;
    }
    float s = sqrt(d);
    float near = -b - s;
    float far = -b + s;
    if (near >= 0)
    {
        return near;
    }
    return far >= 0 ? far : -1;
}

/** x in [0, 1] to the texel-centred coordinate of a table of `size` texels (Bruneton 2017). */
public float unitToTexel(float x, float size)
{
    return 0.5 / size + x * (1 - 1 / size);
}

/** The inverse of unitToTexel. */
public float texelToUnit(float u, float size)
{
    return (u - 0.5 / size) / (1 - 1 / size);
}

/**
 * The transmittance table's coordinates of radius `r` km and cosine `mu` of the zenith angle (Bruneton 2017's
 * GetTransmittanceTextureUvFromRMu): the distance to the top over its range for that radius, and the radius over the
 * horizon's range. Rays below the horizon hit the ground: the table holds rays to the top only.
 */
public float2 transmittanceUv(AtmosphereFrame* a, float r, float mu, float2 size)
{
    float bottom = a[0].bottomRadius;
    float top = a[0].topRadius;
    float h = sqrt(max(top * top - bottom * bottom, 0));
    float rho = sqrt(max(r * r - bottom * bottom, 0));
    float discriminant = r * r * (mu * mu - 1) + top * top;
    float d = max(0, -r * mu + sqrt(max(discriminant, 0)));
    float dMin = top - r;
    float dMax = rho + h;
    float xMu = (d - dMin) / max(dMax - dMin, 1e-6);
    float xR = rho / h;
    return float2(unitToTexel(saturate(xMu), size.x), unitToTexel(saturate(xR), size.y));
}

/** The radius (x) and cosine (y) of a transmittance table texel's coordinates: the inverse of transmittanceUv. */
public float2 transmittanceRMu(AtmosphereFrame* a, float2 uv, float2 size)
{
    float bottom = a[0].bottomRadius;
    float top = a[0].topRadius;
    float xMu = texelToUnit(uv.x, size.x);
    float xR = texelToUnit(uv.y, size.y);
    float h = sqrt(max(top * top - bottom * bottom, 0));
    float rho = h * xR;
    float r = sqrt(rho * rho + bottom * bottom);
    float dMin = top - r;
    float dMax = rho + h;
    float d = dMin + xMu * (dMax - dMin);
    float mu = d == 0 ? 1 : (h * h - rho * rho - d * d) / (2 * r * d);
    return float2(r, clamp(mu, -1, 1));
}

/** The table sizes: width, height. */
public float2 tableSize(uint packed)
{
    return float2(float(packed & 0xffff), float(packed >> 16));
}

/**
 * The transmittance from radius `r` km along a direction of zenith cosine `mu` to the top of the atmosphere, from the
 * table; rays hitting the ground are not in it (see transmittanceToLight).
 */
public float3 transmittance(AtmosphereFrame* a, float r, float mu)
{
    float2 uv = transmittanceUv(a, r, mu, tableSize(a[0].transmittanceSize));
    return textures[a[0].transmittance].SampleLevel(samplers[LINEAR_CLAMP], uv, 0).rgb;
}

/**
 * The transmittance of the light from `direction` (unit, towards it) at point `position` (km from the planet's centre)
 * through the atmosphere, with the planet's shadow: 0 where the ground is in the way. The light's disc sinks below the
 * horizon gradually: the share of a disc of angular radius `radius` above the horizon.
 */
public float3 transmittanceToLight(AtmosphereFrame* a, float3 position, float3 direction, float radius)
{
    float r = length(position);
    float3 up = position / r;
    float mu = dot(up, direction);
    // The horizon's zenith cosine from here: below it the ground blocks the light
    float horizon = -sqrt(max(1 - (a[0].bottomRadius / r) * (a[0].bottomRadius / r), 0));
    float sinHorizon = sqrt(max(1 - horizon * horizon, 0));
    // The angle of the light's centre above the horizon, against the disc's radius
    float above = asin(clamp(mu * sinHorizon - sqrt(max(1 - mu * mu, 0)) * horizon, -1, 1));
    float visible = radius > 0 ? smoothstep(-radius, radius, above) : (above >= 0 ? 1.0 : 0.0);
    if (visible <= 0)
    {
        return 0;
    }
    return transmittance(a, r, max(mu, horizon)) * visible;
}

/** The multiple-scattering table's light Psi_ms at height `h` km for a light of zenith cosine `mu` (Sec. 5.5). */
public float3 multipleScattering(AtmosphereFrame* a, float h, float mu)
{
    float2 size = tableSize(a[0].multipleSize);
    float2 uv = float2(unitToTexel(saturate(mu * 0.5 + 0.5), size.x),
        unitToTexel(saturate(h / (a[0].topRadius - a[0].bottomRadius)), size.y));
    return textures[a[0].multiple].SampleLevel(samplers[LINEAR_CLAMP], uv, 0).rgb;
}

/** Light `i`'s direction (towards it), angular radius and illuminance outside the atmosphere. */
public float3 lightDirection(AtmosphereFrame* a, uint i)
{
    return i == 0 ? a[0].lightDirection0 : a[0].lightDirection1;
}

public float3 lightIlluminance(AtmosphereFrame* a, uint i)
{
    return i == 0 ? a[0].lightIlluminance0 : a[0].lightIlluminance1;
}

public float lightRadius(AtmosphereFrame* a, uint i)
{
    return i == 0 ? a[0].lightRadius0 : a[0].lightRadius1;
}

/** What a ray through the atmosphere gathers: in-scattered radiance, and the transmittance along it. */
public struct Scattered
{
    public float3 radiance;
    public float3 transmittance;
};

/** How finely a ray is integrated: steps between `minSteps` and `maxSteps`, more for longer rays (Hillaire 2020). */
public struct March
{
    public float minSteps;
    public float maxSteps;
    public float stepsPerKm;   // steps grow with the ray's length up to maxSteps
    public bool ground;        // add the lit ground where the ray ends on it
    public bool isotropic;     // the multiple-scattering table's integration: uniform phase, no table, unit light
};

/** The default for views: 4 to 32 steps, one per 3 km (the paper's sky-view pass uses about 30 at the horizon). */
public March defaultMarch()
{
    March m;
    m.minSteps = 4;
    m.maxSteps = 32;
    m.stepsPerKm = 1.0 / 3.0;
    m.ground = true;
    m.isotropic = false;
    return m;
}

/**
 * The light the media at `p` (km from the planet's centre, media `m`) scatter into unit `direction`, per km: each
 * light's single scattering (Rayleigh and Cornette-Shanks phases, the transmittance to it with the planet's shadow)
 * and its multiple scattering Psi_ms times the media's scattering (Hillaire 2020 Eq. 11).
 */
public float3 source(AtmosphereFrame* a, float3 p, float3 direction, Medium m)
{
    float pr = length(p);
    float3 up = p / pr;
    float3 s = 0;
    for (uint l = 0; l < a[0].lightCount; l++)
    {
        float3 toLight = lightDirection(a, l);
        float mu = dot(up, toLight);
        float cosTheta = dot(direction, toLight);
        float3 lit = transmittanceToLight(a, p, toLight, lightRadius(a, l));
        float3 phased = m.rayleigh * rayleighPhase(cosTheta) + m.mie * cornetteShanksPhase(a[0].miePhaseG, cosTheta);
        float3 multiple = a[0].multipleScattering != 0 ? multipleScattering(a, pr - a[0].bottomRadius, mu) : float3(0);
        s += lightIlluminance(a, l) * (lit * phased + multiple * m.scattering);
    }
    return s;
}

/**
 * Integrates the in-scattered light along `direction` from `origin` (km from the planet's centre, inside the
 * atmosphere or on its boundary) between distances `t0` and `t1` km in `steps` steps, onto `radiance` and
 * `throughput` (what came before it along the ray): the aerial perspective's slices, one after another.
 */
public void segment(AtmosphereFrame* a, float3 origin, float3 direction, float t0, float t1, float steps,
    inout float3 radiance, inout float3 throughput)
{
    float dt = (t1 - t0) / steps;
    if (dt <= 0)
    {
        return;
    }
    for (float i = 0; i < steps; i += 1)
    {
        float3 p = origin + direction * (t0 + (i + 0.5) * dt);
        Medium m = medium(a, length(p) - a[0].bottomRadius);
        float3 sampleTransmittance = exp(-m.extinction * dt);
        float3 s = source(a, p, direction, m);
        radiance += throughput * (s - s * sampleTransmittance) / max(m.extinction, 1e-9);
        throughput *= sampleTransmittance;
    }
}

/**
 * The light scattered towards `origin` (km from the planet's centre) along unit `direction`, up to `maxDistance` km
 * (or the ground or the top of the atmosphere, whichever comes first): Hillaire 2020's IntegrateScatteredLuminance,
 * single scattering of each light with the planet's shadow and the transmittance table, plus the multiple scattering
 * Psi_ms of Sec. 5.5 times the media's scattering, integrated per step with Hillaire 2015's energy-conserving form
 * (S - S T) / sigma_t. Where the ray ends on the ground, the ground's light (Lambertian, its albedo, lit through the
 * atmosphere) is added through the ray's transmittance.
 *
 * With `march.isotropic` it computes the multiple-scattering table's two integrals instead (Sec. 5.5, Eq. 6 to 8):
 * `radiance` the second-order light L_2 for a unit light with the uniform phase function, `transmittance` (re-used)
 * the transfer f_ms (the light a unit isotropic source scatters back, before the uniform phase).
 */
public Scattered integrate(AtmosphereFrame* a, float3 origin, float3 direction, float maxDistance, March march,
    float3 isotropicLight)
{
    Scattered result;
    result.radiance = 0;
    result.transmittance = 1;
    float bottom = a[0].bottomRadius;
    float top = a[0].topRadius;
    // Start where the ray enters the atmosphere (from space), end at the ground or where it leaves
    float3 start = origin;
    float r = length(origin);
    if (r > top)
    {
        float enter = raySphere(origin, direction, top);
        if (enter < 0)
        {
            return result; // misses the atmosphere
        }
        start = origin + direction * enter;
        maxDistance -= enter;
        if (maxDistance <= 0)
        {
            return result;
        }
    }
    float toGround = raySphere(start, direction, bottom);
    float toTop = raySphere(start, direction, top + 1e-3);
    bool ground = toGround > 0;
    float rayLength = ground ? toGround : max(toTop, 0);
    bool cut = rayLength > maxDistance;
    rayLength = min(rayLength, maxDistance);
    if (rayLength <= 0)
    {
        return result;
    }
    float steps = clamp(ceil(rayLength * march.stepsPerKm), march.minSteps, march.maxSteps);
    float dt = rayLength / steps;
    float3 throughput = 1;
    float3 radiance = 0;
    float3 transfer = 0;
    float previous = 0;
    for (float i = 0; i < steps; i += 1)
    {
        // Steps growing quadratically along the ray: finest near its start, where the media are densest for rays
        // leaving the ground (the paper's uniform steps need about 8 times as many for the same error at the zenith);
        // the paper's fixed offset 0.3 into each step
        float next = rayLength * ((i + 1) / steps) * ((i + 1) / steps);
        dt = next - previous;
        float t = previous + 0.3 * dt;
        previous = next;
        float3 p = start + direction * t;
        float pr = length(p);
        float3 up = p / pr;
        Medium m = medium(a, pr - bottom);
        float3 sampleTransmittance = exp(-m.extinction * dt);
        float3 safeExtinction = max(m.extinction, 1e-9);
        float3 s = 0;
        if (march.isotropic)
        {
            // The table's integrals: a unit light from isotropicLight, uniform phase, no multiple scattering yet
            float3 lit = transmittanceToLight(a, p, isotropicLight, 0);
            s = lit * m.scattering * uniformPhase();
            transfer += throughput * (m.scattering - m.scattering * sampleTransmittance) / safeExtinction;
        }
        else
        {
            s = source(a, p, direction, m);
        }
        radiance += throughput * (s - s * sampleTransmittance) / safeExtinction;
        throughput *= sampleTransmittance;
    }
    if (ground && !cut && march.ground)
    {
        // The ground: Lambertian, lit by each light through the atmosphere
        float3 p = start + direction * rayLength;
        float3 up = normalize(p);
        if (march.isotropic)
        {
            float3 lit = transmittanceToLight(a, p, isotropicLight, 0);
            radiance += throughput * lit * saturate(dot(up, isotropicLight)) * a[0].groundAlbedo / ATMOSPHERE_PI;
        }
        else
        {
            for (uint l = 0; l < a[0].lightCount; l++)
            {
                float3 toLight = lightDirection(a, l);
                float3 lit = transmittanceToLight(a, p, toLight, lightRadius(a, l));
                radiance += throughput * lightIlluminance(a, l) * lit * saturate(dot(up, toLight))
                    * a[0].groundAlbedo / ATMOSPHERE_PI;
            }
        }
    }
    result.radiance = radiance;
    result.transmittance = march.isotropic ? transfer : throughput;
    return result;
}

/** The camera's position (km from the planet's centre). */
public float3 cameraPosition(AtmosphereFrame* a)
{
    return a[0].camera;
}

/** A camera-relative world position in metres as km from the planet's centre. */
public float3 planetPosition(AtmosphereFrame* a, float3 cameraRelativeMetres)
{
    return a[0].camera + cameraRelativeMetres * 0.001;
}

// The sky-view table (Sec. 5.3): radiance around a point, by azimuth (u, all the way around, absolute: the lights
// differ in azimuth) and the paper's non-linear latitude (v: half the table above the horizon, half below, squared
// towards the horizon where the sky changes most).

/** A sky-view frame: the point's zenith and two horizontal axes, and the horizon's depression there. */
public struct SkyFrame
{
    public float3 up;
    public float3 east;
    public float3 north;
    public float beta;          // the horizon's angle below the horizontal, radians
};

/** The frame of the point `position` (km from the planet's centre), its azimuth measured from world -z (north). */
public SkyFrame skyFrame(AtmosphereFrame* a, float3 position)
{
    SkyFrame f;
    float r = length(position);
    f.up = position / r;
    float3 north = float3(0, 0, -1) - f.up * dot(float3(0, 0, -1), f.up);
    if (dot(north, north) < 1e-8)
    {
        north = float3(1, 0, 0) - f.up * f.up.x;
    }
    f.north = normalize(north);
    f.east = cross(f.north, f.up);
    float horizon = sqrt(max(r * r - a[0].bottomRadius * a[0].bottomRadius, 0));
    f.beta = acos(clamp(horizon / r, -1, 1));
    return f;
}

/** The direction (world) of sky-view coordinates `uv` (0 to 1 each, not texel-centred) in `frame`. */
public float3 skyViewDirection(SkyFrame frame, float2 uv)
{
    float zenithHorizon = ATMOSPHERE_PI - frame.beta;
    float zenith;
    if (uv.y < 0.5)
    {
        float c = 1 - 2 * uv.y;
        c = 1 - c * c;
        zenith = zenithHorizon * c;
    }
    else
    {
        float c = 2 * uv.y - 1;
        zenith = zenithHorizon + frame.beta * c * c;
    }
    float azimuth = uv.x * 2 * ATMOSPHERE_PI;
    float s = sin(zenith);
    return frame.up * cos(zenith) + (frame.north * cos(azimuth) + frame.east * sin(azimuth)) * s;
}

/** The sky-view coordinates (0 to 1, not texel-centred) of world direction `direction` in `frame`. */
public float2 skyViewUv(SkyFrame frame, float3 direction)
{
    float zenithHorizon = ATMOSPHERE_PI - frame.beta;
    float zenith = acos(clamp(dot(direction, frame.up), -1, 1));
    float v;
    if (zenith < zenithHorizon)
    {
        float c = zenith / zenithHorizon;
        v = 0.5 * (1 - sqrt(max(1 - c, 0)));
    }
    else
    {
        float c = (zenith - zenithHorizon) / max(frame.beta, 1e-6);
        v = 0.5 + 0.5 * sqrt(saturate(c));
    }
    float azimuth = atan2(dot(direction, frame.east), dot(direction, frame.north));
    float u = azimuth / (2 * ATMOSPHERE_PI);
    return float2(u < 0 ? u + 1 : u, v);
}

/** The texture coordinate of sky-view coordinate v of a table `height` texels high (u wraps around). */
public float2 skyViewTexel(float2 uv, float2 size)
{
    return float2(uv.x, unitToTexel(uv.y, size.y));
}

/** The radiance of a light's disc at angle cos `cosAngle` from its centre, per its illuminance (limb darkened). */
public float3 discRadiance(float cosAngle, float radius, float centre)
{
    float angle = acos(clamp(cosAngle, -1, 1));
    if (angle >= radius)
    {
        return 0;
    }
    // Limb darkening I(mu) / I(1) = mu^alpha per channel, mu the cosine at the surface of the disc (the power law
    // Hillaire 2016 uses, alpha = 0.397, 0.503, 0.652 for 680, 550, 440 nm). Over a small disc mu^alpha averages
    // 2 / (alpha + 2), so the centre is (alpha + 2) / 2 times the mean: with `centre` 1 / (the disc's solid angle),
    // the disc gives exactly its illuminance in every channel.
    float x = angle / radius;
    float mu = sqrt(max(1 - x * x, 0));
    float3 alpha = float3(0.397, 0.503, 0.652);
    return centre * pow(float3(mu), alpha) * (alpha + 2) * 0.5;
}

/**
 * The night sky's own light along world direction `direction` above the atmosphere, scene units: airglow (a thin
 * emitting layer brightening towards the horizon by the van Rhijn factor, atmosphere-sky's Airglow) and the analytic
 * Milky Way (a band in galactic coordinates, atmosphere-sky's MilkyWay). The caller attenuates it by the atmosphere's
 * transmittance along the ray.
 */
public float3 nightLight(AtmosphereFrame* a, float3 position, float3 direction)
{
    float3 light = 0;
    float r = length(position);
    if (a[0].airglowZenith > 0)
    {
        float cosZenith = dot(position / r, direction);
        float ratio = r / (a[0].bottomRadius + a[0].airglowLayer);
        float sin2 = max(1 - cosZenith * cosZenith, 0);
        float vanRhijn = ratio < 1 ? rsqrt(max(1 - ratio * ratio * sin2, 1e-4)) : 0;
        light += a[0].airglowColor * a[0].airglowZenith * vanRhijn;
    }
    if (a[0].milkyWayCentre > 0)
    {
        float3 g = float3(dot(a[0].galacticX, direction), dot(a[0].galacticY, direction), dot(a[0].galacticZ,
            direction));
        float l = degrees(atan2(g.y, g.x));
        float b = degrees(asin(clamp(g.z, -1, 1)));
        float dl = l / a[0].milkyWayLongitude;
        float along = a[0].milkyWayDisc + (1 - a[0].milkyWayDisc) * exp(-dl * dl);
        float across = exp(-abs(b) / a[0].milkyWayLatitude);
        light += a[0].milkyWayCentre * along * across;
    }
    return light;
}

/** The transmittance from `position` along `direction` out of the atmosphere: 0 into the ground. */
public float3 transmittanceToSpace(AtmosphereFrame* a, float3 position, float3 direction)
{
    float r = length(position);
    if (raySphere(position, direction, a[0].bottomRadius) > 0)
    {
        return 0;
    }
    if (r > a[0].topRadius)
    {
        float enter = raySphere(position, direction, a[0].topRadius);
        if (enter < 0)
        {
            return 1;
        }
        // Through the limb: from the entry, the table's transmittance out again
        float3 p = position + direction * enter;
        return transmittance(a, a[0].topRadius, dot(normalize(p), direction));
    }
    return transmittance(a, r, dot(position / r, direction));
}
