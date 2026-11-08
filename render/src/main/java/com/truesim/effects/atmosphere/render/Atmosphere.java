package com.truesim.effects.atmosphere.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.effects.atmosphere.ephemeris.CelestialBody;
import com.truesim.effects.atmosphere.ephemeris.Ephemeris;
import com.truesim.effects.atmosphere.ephemeris.Galactic;
import com.truesim.effects.atmosphere.ephemeris.Sun;
import com.truesim.effects.atmosphere.render.layout.AtmosphereFrameLayout;
import com.truesim.effects.atmosphere.sky.Airglow;
import com.truesim.effects.atmosphere.sky.MilkyWay;
import com.truesim.effects.atmosphere.sky.NightSky;
import com.truesim.effects.atmosphere.sky.SkyUnits;
import com.truesim.effects.atmosphere.sky.StarColor;
import com.truesim.effects.atmosphere.sky.StarField;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.layout.LightsConstants;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A world's atmosphere as the renderer and other libraries see it each frame: its media ({@link AtmosphereConfig}, the
 * settings of {@code render.sky.atmosphere}), the sun and the moon in the world (from atmosphere-sky's
 * {@link NightSky}, or set by the game), the camera's place in it, and what that makes: the lights' illuminance
 * outside the atmosphere and at the camera, the night sky's frames. It writes the frame's {@code AtmosphereFrame}
 * (atmosphere.slang) into a buffer of its own that every atmosphere pass reads, and the star field into another.
 *
 * <p>The world's frame: metres, +x east, +y up, -z north at the world's origin, which stands {@code originAltitude}
 * above the ground (the observer's altitude) on a planet of {@link AtmosphereConfig#planetRadius} km. Units: scene
 * units are luminance in cd/m² (and illuminance in lux) times {@link #sceneUnits()}, 1 / (1.2 · 2^EV100) (Lagarde and
 * de Rousiers 2014): a game's own lights in physical units take the same factor.
 *
 * <p>For other libraries (clouds, fog): {@link #sunDirection}, {@link #lightIlluminance} (outside the atmosphere),
 * {@link #transmittance} (from any point to a light, the planet's shadow included, on the CPU), the GPU tables through
 * {@link AtmosphereLuts} and {@link SkyViewLut} (atmosphere.slang's functions read them), and {@link #frameAddress}.
 *
 * <pre>{@code
 * Atmosphere atmosphere = new Atmosphere(device, slots, graph.settings());
 * // every frame, after the sky's clock and before the graph records:
 * sky.update(time.julianDayAt(gameSeconds));
 * atmosphere.update(sky, camera.position(0), camera.position(1), camera.position(2));
 * atmosphere.light(lights, 0, 1);                      // the sun and the moon as directional lights
 * }</pre>
 *
 * <p>{@link #update} allocates nothing; a new star catalogue or media do.
 */
public final class Atmosphere implements AutoCloseable {

    /** The sun's radius, km (IAU 2015 nominal). */
    public static final double SUN_RADIUS_KM = 695_700;
    /** The moon's mean radius, km. */
    public static final double MOON_RADIUS_KM = 1737.4;
    /** The sun's colour index B-V (atmosphere-sky's StarColor makes its colour outside the atmosphere). */
    public static final double SUN_COLOR_INDEX = 0.65;
    /** The moon's colour index B-V (its reflectance reddens the sunlight). */
    public static final double MOON_COLOR_INDEX = 0.92;
    /** Bytes a star takes in the star buffer: 8 floats. */
    public static final int STAR_BYTES = 32;

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final EngineSettings settings;
    private final FrameBuffer frame;
    private AtmosphereConfig config;
    private AtmosphereModel model;
    private long mediaVersion = 1;
    private long lightVersion = 1;

    // The lights: towards each (world), outside the atmosphere in scene units, angular radius
    private final double[] sun = {0, 1, 0};
    private final double[] moon = {0, -1, 0};
    private final double[] sunIlluminance = new double[3];
    private final double[] moonIlluminance = new double[3];
    private double sunRadius = 0.004654;
    private double moonRadius = 0.004521;
    private double moonDiscFactor;
    private double sunAltitude;
    private boolean fixed;
    private double fixedLux;
    // The camera, metres in the world; the world's origin's altitude, metres
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private double originAltitude;
    // Rows of the world from the equatorial frame of date, and of the galactic frame from the world
    private final double[] worldFromEquatorial = {1, 0, 0, 0, 0, 1, 0, -1, 0};
    private final double[] galacticFromWorld = new double[9];
    private final double[] galacticFromEquatorial = new double[9];
    private Airglow airglow;
    private MilkyWay milkyWay;
    // The stars: their buffer and what it holds
    private FrameBuffer stars;
    private final List<FrameBuffer> retired = new ArrayList<>();
    private Arena starArena;
    private MemorySegment starStaging;
    private StarField starField;
    private double starsUploadedJde = Double.NaN;
    private int starCount;
    private final double[] scratch = new double[3];
    private final double[] colour = new double[3];
    private final float[] rgb = new float[3];

    /** An atmosphere on {@code device}, its frame written in {@code slots}, its media from {@code settings}. */
    public Atmosphere(VulkanDevice device, FrameSlots slots, EngineSettings settings) {
        this.device = Objects.requireNonNull(device, "device");
        this.slots = Objects.requireNonNull(slots, "slots");
        this.settings = Objects.requireNonNull(settings, "settings");
        frame = FrameBuffer.create(device, AtmosphereFrameLayout.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        stars = starBuffer(1);
        double[] e = new double[3];
        double[] g = new double[3];
        for (int j = 0; j < 3; j++) {
            e[0] = j == 0 ? 1 : 0;
            e[1] = j == 1 ? 1 : 0;
            e[2] = j == 2 ? 1 : 0;
            Galactic.fromEquatorial(e, 0, g, 0);
            for (int i = 0; i < 3; i++) {
                galacticFromEquatorial[3 * i + j] = g[i];
            }
        }
        refresh();
        updateGalactic();
    }

    /** The settings it reads its media from. */
    public EngineSettings settings() {
        return settings;
    }

    /** The device it draws on. */
    public VulkanDevice device() {
        return device;
    }

    /** The atmosphere's media and options now. */
    public AtmosphereConfig config() {
        return config;
    }

    /** The CPU model of the media now. */
    public AtmosphereModel model() {
        return model;
    }

    /** Changes when the media change: the transmittance and multiple-scattering tables are made again. */
    public long mediaVersion() {
        return mediaVersion;
    }

    /** Changes with every change of the lights or the exposure (the sky capture's cue). */
    public long lightVersion() {
        return lightVersion;
    }

    /** Scene units per lux (and per cd/m²): 1 / (1.2 · 2^EV100) of the config's exposure. */
    public double sceneUnits() {
        return 1.0 / (1.2 * Maths.pow(2, config.ev100()));
    }

    /**
     * Uses a fixed sun from now on, towards ({@code x}, {@code y}, {@code z}) in the world with {@code lux} outside
     * the atmosphere and no moon, stars or night sky: a game not following the ephemeris ({@link #update} with a null
     * sky). Null-free: call {@link #followSky} to follow a sky again.
     */
    public Atmosphere fixedSun(double x, double y, double z, double lux) {
        double l = Math.sqrt(x * x + y * y + z * z);
        if (!(l > 0) || !(lux >= 0)) {
            throw new IllegalArgumentException("A direction and lux of 0 or more: " + l + ", " + lux);
        }
        sun[0] = x / l;
        sun[1] = y / l;
        sun[2] = z / l;
        fixedLux = lux;
        fixed = true;
        return this;
    }

    /** Follows the sky given to {@link #update} again. */
    public Atmosphere followSky() {
        fixed = false;
        return this;
    }

    /**
     * Takes this frame's sky and camera: the sun, the moon and the night sky from {@code sky} (its ephemeris updated
     * for the frame's date; null with a {@link #fixedSun}), the camera at ({@code x}, {@code y}, {@code z}) metres in
     * the world. Writes the frame's atmosphere. Allocates nothing.
     */
    public void update(NightSky sky, double x, double y, double z) {
        refresh();
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        double units = sceneUnits();
        double oldSun0 = sunIlluminance[1];
        double oldMoon0 = moonIlluminance[1];
        double sx = sun[0];
        double sy = sun[1];
        double sz = sun[2];
        double mx = moon[0];
        double my = moon[1];
        double mz = moon[2];
        if (sky != null && !fixed) {
            Ephemeris eph = sky.ephemeris();
            originAltitude = eph.observer().altitude();
            toWorld(eph.sun(), sun);
            toWorld(eph.moon(), moon);
            sunRadius = Maths.asin(Math.min(1, SUN_RADIUS_KM / eph.sun().distanceKm()));
            moonRadius = Maths.asin(Math.min(1, MOON_RADIUS_KM / eph.moon().distanceKm()));
            illuminance(SUN_COLOR_INDEX, eph.sunIlluminance() * units, sunIlluminance);
            illuminance(MOON_COLOR_INDEX, config.moonDisc() ? eph.moonIlluminance() * units : 0, moonIlluminance);
            double[] h = eph.equatorialToHorizon();
            for (int c = 0; c < 3; c++) {
                worldFromEquatorial[c] = h[c];          // x: east
                worldFromEquatorial[3 + c] = h[6 + c];  // y: up
                worldFromEquatorial[6 + c] = -h[3 + c]; // z: south
            }
            updateGalactic();
            airglow = sky.settings().airglow();
            milkyWay = sky.settings().milkyWay();
            starField = sky.stars();
        } else {
            sunRadius = Maths.asin(SUN_RADIUS_KM / Sun.AU_KM);
            illuminance(SUN_COLOR_INDEX, fixedLux * units, sunIlluminance);
            illuminance(MOON_COLOR_INDEX, 0, moonIlluminance);
            airglow = null;
            milkyWay = null;
            starField = null;
        }
        sunAltitude = Maths.toDegrees(Maths.asin(Maths.clamp(sun[1], -1, 1)));
        moonDiscFactor = moonDisc();
        double moved = Math.max(angle(sx, sy, sz, sun), moonIlluminance[1] > 0 ? angle(mx, my, mz, moon) : 0);
        if (moved > config.captureAngle() || changed(oldSun0, sunIlluminance[1]) || changed(oldMoon0,
            moonIlluminance[1])) {
            lightVersion++;
        }
        uploadStars();
        write();
    }

    /** The relative change from {@code before} to {@code now} beyond the capture's threshold. */
    private boolean changed(double before, double now) {
        double most = Math.max(Math.abs(before), Math.abs(now));
        return most > 0 && Math.abs(now - before) > config.captureChange() * most;
    }

    /** Degrees between the unit vectors ({@code x}, {@code y}, {@code z}) and {@code v}. */
    private static double angle(double x, double y, double z, double[] v) {
        return Maths.toDegrees(Maths.acos(Maths.clamp(x * v[0] + y * v[1] + z * v[2], -1, 1)));
    }

    /** The settings' config again: new media are a new model and version. */
    private void refresh() {
        AtmosphereConfig now = AtmosphereSky.ATMOSPHERE.config(settings);
        if (now != config) {
            if (config == null || !now.sameMedia(config)) {
                newModel(now);
                mediaVersion++;
            }
            if (config != null && config.ev100() != now.ev100()) {
                lightVersion++;
            }
            config = now;
        }
    }

    @AllowAllocation("the media changed")
    private void newModel(AtmosphereConfig now) {
        model = new AtmosphereModel(now);
    }

    /** {@code body}'s direction in the world: east, up, south. */
    private static void toWorld(CelestialBody body, double[] out) {
        out[0] = body.east();
        out[1] = body.up();
        out[2] = -body.north();
    }

    /** {@code lux} (scene units) times the colour of B-V {@code colorIndex}, into {@code out}. */
    private void illuminance(double colorIndex, double lux, double[] out) {
        StarColor.linearSrgb(colorIndex, rgb, 0);
        out[0] = lux * rgb[0];
        out[1] = lux * rgb[1];
        out[2] = lux * rgb[2];
    }

    /** The galactic frame from the world: G times the transpose of the world from the equatorial. */
    private void updateGalactic() {
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                double s = 0;
                for (int j = 0; j < 3; j++) {
                    s += galacticFromEquatorial[3 * i + j] * worldFromEquatorial[3 * k + j];
                }
                galacticFromWorld[3 * i + k] = s;
            }
        }
    }

    /**
     * The moon disc's Lommel-Seeliger factor (sky.slang's moonDisc): 1 / the integral over the disc of
     * cos i / (cos i + cos e), so that the disc gives the moon's illuminance; integrated on a 32 x 32 grid.
     */
    private double moonDisc() {
        // A frame about the moon's direction
        double ax = Math.abs(moon[1]) < 0.9 ? 0 : 1;
        double ay = Math.abs(moon[1]) < 0.9 ? 1 : 0;
        double rx = ay * moon[2];
        double ry = -ax * moon[2];
        double rz = ax * moon[1] - ay * moon[0];
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double ux = moon[1] * rz - moon[2] * ry;
        double uy = moon[2] * rx - moon[0] * rz;
        double uz = moon[0] * ry - moon[1] * rx;
        int n = 32;
        double sinR = Maths.sin(moonRadius);
        double cell = 2.0 / n;
        double sum = 0;
        for (int j = 0; j < n; j++) {
            double v = -1 + (j + 0.5) * cell;
            for (int i = 0; i < n; i++) {
                double u = -1 + (i + 0.5) * cell;
                double rr = u * u + v * v;
                if (rr >= 1) {
                    continue;
                }
                double w = Math.sqrt(1 - rr);
                double nx = u * rx + v * ux - w * moon[0];
                double ny = u * ry + v * uy - w * moon[1];
                double nz = u * rz + v * uz - w * moon[2];
                double cosI = nx * sun[0] + ny * sun[1] + nz * sun[2];
                if (cosI <= 0) {
                    continue;
                }
                double cosE = w;
                sum += cosI / (cosI + cosE);
            }
        }
        double area = sum * (cell * sinR) * (cell * sinR);
        return area > 0 ? 1 / area : 0;
    }

    /** Whether the moon lights the sky this frame: disc on, and up enough to matter. */
    private boolean moonLights() {
        return moonIlluminance[1] > 0 && moon[1] > -0.2;
    }

    /** Writes the frame's AtmosphereFrame. */
    private void write() {
        AtmosphereConfig c = config;
        put3(AtmosphereFrameLayout.RAYLEIGH_SCATTERING, c.rayleighRed(), c.rayleighGreen(), c.rayleighBlue());
        frame.putFloat(AtmosphereFrameLayout.BOTTOM_RADIUS, c.planetRadius());
        put3(AtmosphereFrameLayout.MIE_SCATTERING, c.mieScattering(), c.mieScattering(), c.mieScattering());
        frame.putFloat(AtmosphereFrameLayout.TOP_RADIUS, c.topRadius());
        float mieExtinction = c.mieScattering() + c.mieAbsorption();
        put3(AtmosphereFrameLayout.MIE_EXTINCTION, mieExtinction, mieExtinction, mieExtinction);
        frame.putFloat(AtmosphereFrameLayout.RAYLEIGH_SCALE_HEIGHT, c.rayleighScaleHeight());
        put3(AtmosphereFrameLayout.OZONE_ABSORPTION, c.ozoneRed(), c.ozoneGreen(), c.ozoneBlue());
        frame.putFloat(AtmosphereFrameLayout.MIE_SCALE_HEIGHT, c.mieScaleHeight());
        put3(AtmosphereFrameLayout.GROUND_ALBEDO, c.groundAlbedo(), c.groundAlbedo(), c.groundAlbedo());
        frame.putFloat(AtmosphereFrameLayout.MIE_PHASE_G, c.mieAnisotropy());
        frame.putFloat(AtmosphereFrameLayout.OZONE_CENTER, c.ozoneCenter());
        frame.putFloat(AtmosphereFrameLayout.OZONE_HALF_WIDTH, c.ozoneHalfWidth());
        frame.putInt(AtmosphereFrameLayout.LIGHT_COUNT, moonLights() ? 2 : 1);
        frame.putInt(AtmosphereFrameLayout.MULTIPLE_SCATTERING, c.multipleScattering() ? 1 : 0);
        double r = c.planetRadius() + originAltitude * 0.001;
        put3(AtmosphereFrameLayout.CAMERA, cameraX * 0.001, r + cameraY * 0.001, cameraZ * 0.001);
        frame.putFloat(AtmosphereFrameLayout.ORIGIN_ALTITUDE, (float) (originAltitude * 0.001));
        put3(AtmosphereFrameLayout.LIGHT_DIRECTION0, sun[0], sun[1], sun[2]);
        frame.putFloat(AtmosphereFrameLayout.LIGHT_RADIUS0, (float) sunRadius);
        put3(AtmosphereFrameLayout.LIGHT_ILLUMINANCE0, sunIlluminance[0], sunIlluminance[1], sunIlluminance[2]);
        frame.putFloat(AtmosphereFrameLayout.LIGHT_DISC0, (float) (1 / solidAngle(sunRadius)));
        put3(AtmosphereFrameLayout.LIGHT_DIRECTION1, moon[0], moon[1], moon[2]);
        frame.putFloat(AtmosphereFrameLayout.LIGHT_RADIUS1, (float) moonRadius);
        put3(AtmosphereFrameLayout.LIGHT_ILLUMINANCE1, moonIlluminance[0], moonIlluminance[1], moonIlluminance[2]);
        frame.putFloat(AtmosphereFrameLayout.LIGHT_DISC1, (float) (1 / solidAngle(moonRadius)));
        frame.putInt(AtmosphereFrameLayout.TRANSMITTANCE_SIZE, c.transmittanceWidth() | c.transmittanceHeight() << 16);
        frame.putInt(AtmosphereFrameLayout.MULTIPLE_SIZE, c.multipleScatteringSize()
            | c.multipleScatteringSize() << 16);
        double units = sceneUnits();
        if (airglow != null && airglow.enabled()) {
            double luma = 0.2126 * airglow.red() + 0.7152 * airglow.green() + 0.0722 * airglow.blue();
            put3(AtmosphereFrameLayout.AIRGLOW_COLOR, airglow.red() / luma, airglow.green() / luma,
                airglow.blue() / luma);
            frame.putFloat(AtmosphereFrameLayout.AIRGLOW_ZENITH, (float) (SkyUnits.luminance(
                airglow.zenithMagnitude()) * units));
            frame.putFloat(AtmosphereFrameLayout.AIRGLOW_LAYER, (float) airglow.layerHeightKm());
        } else {
            frame.putFloat(AtmosphereFrameLayout.AIRGLOW_ZENITH, 0);
            frame.putFloat(AtmosphereFrameLayout.AIRGLOW_LAYER, 90);
        }
        if (milkyWay != null && milkyWay.source() == MilkyWay.Source.ANALYTIC) {
            frame.putFloat(AtmosphereFrameLayout.MILKY_WAY_CENTRE, (float) (milkyWay.brightness()
                * SkyUnits.luminance(milkyWay.centreMagnitude()) * units));
            frame.putFloat(AtmosphereFrameLayout.MILKY_WAY_LATITUDE, (float) milkyWay.latitudeScaleDegrees());
            frame.putFloat(AtmosphereFrameLayout.MILKY_WAY_LONGITUDE, (float) milkyWay.longitudeScaleDegrees());
            frame.putFloat(AtmosphereFrameLayout.MILKY_WAY_DISC, (float) milkyWay.discFraction());
        } else {
            frame.putFloat(AtmosphereFrameLayout.MILKY_WAY_CENTRE, 0);
            frame.putFloat(AtmosphereFrameLayout.MILKY_WAY_LATITUDE, 1);
            frame.putFloat(AtmosphereFrameLayout.MILKY_WAY_LONGITUDE, 1);
        }
        put3(AtmosphereFrameLayout.GALACTIC_X, galacticFromWorld[0], galacticFromWorld[1], galacticFromWorld[2]);
        put3(AtmosphereFrameLayout.GALACTIC_Y, galacticFromWorld[3], galacticFromWorld[4], galacticFromWorld[5]);
        put3(AtmosphereFrameLayout.GALACTIC_Z, galacticFromWorld[6], galacticFromWorld[7], galacticFromWorld[8]);
        put3(AtmosphereFrameLayout.EQUATORIAL_X, worldFromEquatorial[0], worldFromEquatorial[1],
            worldFromEquatorial[2]);
        put3(AtmosphereFrameLayout.EQUATORIAL_Y, worldFromEquatorial[3], worldFromEquatorial[4],
            worldFromEquatorial[5]);
        put3(AtmosphereFrameLayout.EQUATORIAL_Z, worldFromEquatorial[6], worldFromEquatorial[7],
            worldFromEquatorial[8]);
    }

    /** The solid angle of a disc of angular radius {@code radius}, sr. */
    static double solidAngle(double radius) {
        return 2 * Maths.PI * (1 - Maths.cos(radius));
    }

    private void put3(long offset, double x, double y, double z) {
        frame.putFloat(offset, (float) x);
        frame.putFloat(offset + 4, (float) y);
        frame.putFloat(offset + 8, (float) z);
    }

    /** Writes the tables' sampled slots into this frame's atmosphere (as the passes reading them record). */
    void tables(int transmittance, int multiple) {
        frame.putInt(AtmosphereFrameLayout.TRANSMITTANCE, transmittance);
        frame.putInt(AtmosphereFrameLayout.MULTIPLE, multiple);
    }

    /** Writes the transmittance table's sampled slot alone (the multiple-scattering table's pass). */
    void transmittanceSlot(int transmittance) {
        frame.putInt(AtmosphereFrameLayout.TRANSMITTANCE, transmittance);
    }

    /** The device address of this frame's AtmosphereFrame (atmosphere.slang): valid for the frame recording now. */
    public long frameAddress() {
        return frame.deviceAddress();
    }

    // ---------------------------------------------------------------- the stars

    @AllowAllocation("a star catalogue larger than any before")
    private FrameBuffer starBuffer(int capacity) {
        if (starArena != null) {
            starArena.close();
        }
        starArena = Arena.ofShared();
        starStaging = starArena.allocate((long) STAR_BYTES * capacity, 16);
        return FrameBuffer.create(device, (long) STAR_BYTES * capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
    }

    /** The star field's places into the star buffer when they changed: written in memory, put in one piece. */
    private void uploadStars() {
        StarField field = starField;
        if (field == null || !config.stars()) {
            starCount = 0;
            return;
        }
        if (field.updatedJde() == starsUploadedJde && starCount == field.count()) {
            return;
        }
        int count = field.count();
        if ((long) count * STAR_BYTES > stars.size()) {
            retired.add(stars);
            stars = starBuffer(count);
        }
        float[] d = field.directions();
        float[] e = field.illuminance();
        float[] c = field.colors();
        for (int i = 0; i < count; i++) {
            long o = (long) i * STAR_BYTES;
            starStaging.set(F32, o, d[3 * i]);
            starStaging.set(F32, o + 4, d[3 * i + 1]);
            starStaging.set(F32, o + 8, d[3 * i + 2]);
            starStaging.set(F32, o + 12, e[i]);
            starStaging.set(F32, o + 16, c[3 * i]);
            starStaging.set(F32, o + 20, c[3 * i + 1]);
            starStaging.set(F32, o + 24, c[3 * i + 2]);
            starStaging.set(F32, o + 28, 0f);
        }
        stars.put(0, starStaging, 0, (long) count * STAR_BYTES);
        starCount = count;
        starsUploadedJde = field.updatedJde();
    }

    /** The stars to draw this frame: none by day (the sun above {@code starsBelowSunAltitude}). */
    public int starCount() {
        return sunAltitude < config.starsBelowSunAltitude() ? starCount : 0;
    }

    /** The star buffer's device address: valid for the frame recording now. */
    public long starsAddress() {
        return stars.deviceAddress();
    }

    /** Row {@code row}, column {@code column} of the rotation from the equatorial frame of date into the world. */
    public double worldFromEquatorial(int row, int column) {
        return worldFromEquatorial[3 * row + column];
    }

    // ---------------------------------------------------------------- what others read

    /** The sun's direction (towards it, world, unit) into {@code out}. */
    public void sunDirection(double[] out) {
        out[0] = sun[0];
        out[1] = sun[1];
        out[2] = sun[2];
    }

    /** The moon's direction (towards it, world, unit) into {@code out}. */
    public void moonDirection(double[] out) {
        out[0] = moon[0];
        out[1] = moon[1];
        out[2] = moon[2];
    }

    /** The sun's altitude above the world's horizon, degrees. */
    public double sunAltitude() {
        return sunAltitude;
    }

    /** The sun's (0) or the moon's (1) angular radius, radians. */
    public double lightRadius(int light) {
        return light == 0 ? sunRadius : moonRadius;
    }

    /** The moon disc's Lommel-Seeliger factor, 1/sr (sky.slang). */
    public double moonDiscFactor() {
        return moonDiscFactor;
    }

    /** The sun's (0) or the moon's (1) illuminance outside the atmosphere, scene units, into {@code out} (rgb). */
    public void lightIlluminance(int light, double[] out) {
        double[] from = light == 0 ? sunIlluminance : moonIlluminance;
        out[0] = from[0];
        out[1] = from[1];
        out[2] = from[2];
    }

    /** The camera's world position, metres, as last given to {@link #update}. */
    public double cameraX() {
        return cameraX;
    }

    public double cameraY() {
        return cameraY;
    }

    public double cameraZ() {
        return cameraZ;
    }

    /** The world origin's altitude above the ground, metres (the observer's). */
    public double originAltitude() {
        return originAltitude;
    }

    /** The camera's height above the ground, km. */
    public double cameraAltitudeKm() {
        return (originAltitude + cameraY) * 0.001;
    }

    /**
     * The transmittance from height {@code altitudeKm} above the ground (at the world's origin's zenith) to the sun
     * (0) or the moon (1), the planet's shadow and the disc's share above the horizon included, into {@code out}.
     */
    public void transmittance(int light, double altitudeKm, double[] out) {
        double[] d = light == 0 ? sun : moon;
        model.transmittanceToLight(config.planetRadius() + Math.max(altitudeKm, 0.0005), d[1], lightRadius(light),
            out);
    }

    /**
     * The sun's (0) or the moon's (1) illuminance at the camera, scene units, on a surface facing it: outside the
     * atmosphere times the transmittance, into {@code out}. What lights a scene directly.
     */
    public void lightAtCamera(int light, double[] out) {
        transmittance(light, cameraAltitudeKm(), scratch);
        double[] from = light == 0 ? sunIlluminance : moonIlluminance;
        out[0] = from[0] * scratch[0];
        out[1] = from[1] * scratch[1];
        out[2] = from[2] * scratch[2];
    }

    /**
     * Sets the sun and the moon as directional lights {@code sunIndex} and {@code moonIndex} of {@code lights}
     * (negative: not set): shining from their directions with their illuminance at the camera, only where it changed
     * (a light written again restarts what caches it). Allocates nothing.
     */
    public void light(Lights lights, int sunIndex, int moonIndex) {
        if (sunIndex >= 0) {
            set(lights, sunIndex, 0, sun);
        }
        if (moonIndex >= 0) {
            set(lights, moonIndex, 1, moon);
        }
    }

    private void set(Lights lights, int index, int light, double[] direction) {
        lightAtCamera(light, colour);
        double[] was = scratch;
        lights.direction(index, was);
        float brightness = lights.brightness(index);
        double now = Math.max(colour[0], Math.max(colour[1], colour[2]));
        boolean same = lights.type(index) == LightsConstants.DIRECTIONAL
            && Math.abs(was[0] + direction[0]) < 1e-6 && Math.abs(was[1] + direction[1]) < 1e-6
            && Math.abs(was[2] + direction[2]) < 1e-6 && Math.abs(brightness - now) <= 1e-6 * Math.max(now, 1e-30);
        if (!same) {
            lights.directional(index, (float) -direction[0], (float) -direction[1], (float) -direction[2],
                (float) colour[0], (float) colour[1], (float) colour[2]);
        }
    }

    @Override
    public void close() {
        frame.close();
        stars.close();
        for (int i = 0; i < retired.size(); i++) {
            retired.get(i).close();
        }
        retired.clear();
        if (starArena != null) {
            starArena.close();
            starArena = null;
        }
    }
}
