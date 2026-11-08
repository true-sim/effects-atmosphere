package com.truesim.effects.atmosphere.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The atmosphere's sky against an independent reference ({@link ReferenceAtmosphere}: the same media path traced,
 * every order of scattering, no tables) in Hillaire 2020's Earth setup, and its behaviour where only the physics
 * explains it: sunsets redden towards the sun, the earth's shadow darkens the sky after sunset, night is dark, space
 * black, the planet lit from orbit.
 */
class AtmosphereSkyTest {

    private static final int SIZE = 16;
    private static final double GROUND = 6360.0005; // the tables' camera, just above the ground

    private TestGpu gpu;
    private AtmosphereRig rig;
    private final ReferenceAtmosphere reference = new ReferenceAtmosphere(new AtmosphereModel(AtmosphereConfig.EARTH));

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        rig = gpu.closing(new AtmosphereRig(gpu.device(), SIZE, SIZE));
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close(); // fails on validation errors
        }
    }

    /** The sky's radiance along unit ({@code x}, {@code y}, {@code z}) per unit sun illuminance, each channel. */
    private double[] sky(double x, double y, double z, double cameraY) {
        double l = Math.sqrt(x * x + y * y + z * z);
        x /= l;
        y /= l;
        z /= l;
        boolean vertical = Math.abs(y) > 0.9;
        rig.camera.position(0, cameraY, 0).set(x, y, z, 0, vertical ? 0 : 1, vertical ? -1 : 0, Math.toRadians(2),
            SIZE, SIZE, 0.1);
        rig.frames(null, 3);
        float[][] rgb = rig.read();
        double[] e = new double[3];
        rig.atmosphere.lightIlluminance(0, e);
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            out[c] = (rgb[c][(SIZE / 2) * SIZE + SIZE / 2] + rgb[c][(SIZE / 2 - 1) * SIZE + SIZE / 2 - 1]) / 2 / e[c];
        }
        return out;
    }

    private double[] referenceSky(double r, double x, double y, double z, double[] sun, int paths) {
        double l = Math.sqrt(x * x + y * y + z * z);
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            out[c] = reference.radiance(r, x / l, y / l, z / l, sun[0], sun[1], sun[2], c, paths, 11 + c);
        }
        return out;
    }

    private static double luminance(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private double[] sun(double elevationDegrees, double azimuthDegrees) {
        double e = Math.toRadians(elevationDegrees);
        double a = Math.toRadians(azimuthDegrees);
        double[] s = {Math.sin(a) * Math.cos(e), Math.sin(e), -Math.cos(a) * Math.cos(e)};
        rig.atmosphere.fixedSun(s[0], s[1], s[2], 120_000);
        return s;
    }

    private static void report(String what, double[] gpuValue, double[] ref) {
        System.out.printf(Locale.ROOT, "%s: gpu %.5f %.5f %.5f, reference %.5f %.5f %.5f, luminance %.1f %%%n", what,
            gpuValue[0], gpuValue[1], gpuValue[2], ref[0], ref[1], ref[2],
            100 * (luminance(gpuValue) - luminance(ref)) / luminance(ref));
    }

    @Test
    void theSkyAtTheZenithAndTheHorizonMatchesThePathTracedAtmosphere() {
        double[] s = sun(30, 0);
        double[][] directions = {{0, 1, 0}, {1, Math.tan(Math.toRadians(5)), 0}, {0, Math.tan(Math.toRadians(5)), -1},
            {0, Math.tan(Math.toRadians(20)), 1}};
        String[] names = {"zenith", "horizon across", "horizon towards the sun", "20 degrees up away from the sun"};
        for (int i = 0; i < directions.length; i++) {
            double[] d = directions[i];
            double[] g = sky(d[0], d[1], d[2], 0);
            double[] ref = referenceSky(GROUND, d[0], d[1], d[2], s, 30_000);
            report(names[i], g, ref);
            // Hillaire 2020's multiple scattering (isotropic, from the 64-direction table) within 10 % of luminance
            // and 12 % a channel: it misses some of the blue light scattered many times along the horizon
            assertThat(luminance(g)).as(names[i]).isCloseTo(luminance(ref), within(0.10 * luminance(ref)));
            for (int c = 0; c < 3; c++) {
                assertThat(g[c]).as(names[i] + " channel " + c).isCloseTo(ref[c], within(0.12 * ref[c]));
            }
        }
        // The sky is blue overhead
        double[] zenith = sky(0, 1, 0, 0);
        assertThat(zenith[2]).isGreaterThan(2 * zenith[0]);
    }

    @Test
    void singleScatteringMatchesThePathTracerToAFewPercent() {
        rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Boolean>setting("multipleScattering"), false);
        rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("groundAlbedo"), 0f);
        double[] s = sun(30, 0);
        reference.order(1);
        for (double[] d : new double[][] {{0, 1, 0}, {1, Math.tan(Math.toRadians(5)), 0}}) {
            double[] g = sky(d[0], d[1], d[2], 0);
            double[] ref = referenceSky(GROUND, d[0], d[1], d[2], s, 30_000);
            report("single scattering", g, ref);
            for (int c = 0; c < 3; c++) {
                assertThat(g[c]).isCloseTo(ref[c], within(0.07 * ref[c]));
            }
        }
    }

    @Test
    void aSettingSunReddensTheHorizonTowardsItAndLeavesTheZenithBlue() {
        double[] s = sun(1, 0);
        double[] towards = sky(0, Math.tan(Math.toRadians(2)), -1, 0);
        double[] zenith = sky(0, 1, 0, 0);
        double[] ref = referenceSky(GROUND, 0, Math.tan(Math.toRadians(2)), -1, s, 30_000);
        report("sunset towards the sun", towards, ref);
        // Red over green over blue towards the setting sun, as the path tracer has it
        assertThat(towards[0]).isGreaterThan(1.3 * towards[1]);
        assertThat(towards[1]).isGreaterThan(1.3 * towards[2]);
        assertThat(towards[0] / towards[1]).isCloseTo(ref[0] / ref[1], within(0.15 * ref[0] / ref[1]));
        assertThat(zenith[2]).isGreaterThan(zenith[0]);
        // The sun's own light at the ground is red at sunset: the transmittance of the light set by the atmosphere
        double[] light = new double[3];
        rig.atmosphere.lightAtCamera(0, light);
        assertThat(light[0]).isGreaterThan(4 * light[2]);
    }

    @Test
    void theEarthsShadowDarkensTheSkyAfterSunsetAndNightIsDark() {
        sun(30, 0);
        double noon = luminance(sky(0, 1, 0, 0));
        sun(-4, 0);
        double twilight = luminance(sky(0, 1, 0, 0));
        sun(-25, 0);
        double night = luminance(sky(0, 1, 0, 0));
        System.out.printf(Locale.ROOT, "zenith: day %.3g, sun -4 degrees %.3g, -25 degrees %.3g (per unit sun)%n", noon,
            twilight, night);
        // In the earth's shadow the zenith still sees sunlit air high up (twilight), a few hundred times darker
        assertThat(twilight).isPositive().isLessThan(noon / 30).isGreaterThan(noon / 3000);
        // Deep night: no sunlight reaches any air the zenith ray crosses
        assertThat(night).isLessThan(noon * 1e-6);
    }

    @Test
    void fromOrbitSpaceIsBlackAndThePlanetBelowIsLitAsThePathTracerHasIt() {
        double[] s = sun(60, 0);
        double up = luminance(sky(0, 1, 0, 400_000));
        assertThat(up).isLessThan(1e-9);
        double[] down = sky(0, -1, 0, 400_000);
        double[] ref = referenceSky(6760, 0, -1, 0, s, 20_000);
        report("nadir from 400 km", down, ref);
        assertThat(luminance(down)).isCloseTo(luminance(ref), within(0.1 * luminance(ref)));
    }

    @Test
    void theGradientKindDrawsItsColoursWithoutTables() {
        AtmosphereSky.SLOT.choose(rig.graph.settings(), AtmosphereSky.GRADIENT);
        sun(40, 0);
        rig.camera.position(0, 2, 0).set(0, 1, 0, 0, 0, -1, Math.toRadians(2), SIZE, SIZE, 0.1);
        rig.frames(null, 3);
        double[] zenith = rig.pixel(rig.read(), SIZE / 2, SIZE / 2);
        double units = rig.atmosphere.sceneUnits();
        AtmosphereSky.Gradient g = AtmosphereSky.GRADIENT.config(rig.graph.settings());
        System.out.printf(Locale.ROOT, "gradient zenith %.4f %.4f %.4f, units %.4g, ev %.2f, config %s%n", zenith[0],
            zenith[1], zenith[2], units, rig.atmosphere.config().ev100(), g);
        assertThat(zenith[2]).isCloseTo(g.zenithBlue() * units, within(0.02 * g.zenithBlue() * units));
        assertThat(zenith[0]).isCloseTo(g.zenithRed() * units, within(0.02 * g.zenithRed() * units));
    }
}
