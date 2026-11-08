package com.truesim.effects.atmosphere.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import com.truesim.effects.atmosphere.ephemeris.Ephemeris;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import com.truesim.effects.atmosphere.sky.NightSky;
import com.truesim.effects.atmosphere.sky.SkySettings;
import com.truesim.effects.atmosphere.sky.StarCatalogue;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The night sky drawn: a star gives the sky exactly its illuminance (through the atmosphere), whatever pixels it falls
 * on; the moon's disc gives the moon's illuminance and is lit on the side facing the sun; airglow lights a moonless
 * night's sky; by day there are no stars.
 */
class NightSkyTest {

    private static final int SIZE = 32;
    private static final Observer VIENNA = new Observer(48.21, 16.37, 0);

    private TestGpu gpu;
    private AtmosphereRig rig;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        rig = gpu.closing(new AtmosphereRig(gpu.device(), SIZE, SIZE));
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close();
        }
    }

    /** The first moment from 2026-09-01 18:00 UT, every quarter hour, the condition holds. */
    private static double find(java.util.function.Predicate<Ephemeris> condition) {
        Ephemeris e = new Ephemeris(SkySettings.defaults().ephemeris());
        e.setObserver(VIENNA);
        double from = AstroTime.julianDay(2026, 9, 1, 18, 0, 0);
        for (double jd = from; jd < from + 200; jd += 1.0 / 96) {
            e.update(jd);
            if (condition.test(e)) {
                return jd;
            }
        }
        throw new IllegalStateException("never");
    }

    /** Points the camera along world ({@code x}, {@code y}, {@code z}) with a field of {@code fov} degrees. */
    private void look(double x, double y, double z, double fov) {
        boolean vertical = Math.abs(y) > 0.9;
        rig.camera.position(0, 0, 0).set(x, y, z, 0, vertical ? 0 : 1, vertical ? -1 : 0, Math.toRadians(fov), SIZE,
            SIZE, 0.1);
    }

    /** The luminance of the image summed over its pixels times their solid angle: its illuminance, scene units. */
    private double illuminance(float[][] rgb, double fov) {
        double half = Math.tan(Math.toRadians(fov) / 2);
        double sum = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = ((x + 0.5) / SIZE * 2 - 1) * half;
                double v = ((y + 0.5) / SIZE * 2 - 1) * half;
                double cell = 2 * half / SIZE;
                double omega = cell * cell / Math.pow(1 + u * u + v * v, 1.5);
                int i = y * SIZE + x;
                sum += (0.2126 * rgb[0][i] + 0.7152 * rgb[1][i] + 0.0722 * rgb[2][i]) * omega;
            }
        }
        return sum;
    }

    @Test
    void aStarGivesTheSkyItsIlluminanceThroughTheAtmosphere() {
        double jd = find(e -> e.sun().altitude() < -25 && e.moon().altitude() < -10);
        NightSky sky = new NightSky(SkySettings.forQuality(Quality.HIGH, VIENNA));
        sky.update(jd);
        // One star of magnitude 1, colour index 0.6, placed at the zenith of that moment (J2000 near enough)
        double[] h = sky.ephemeris().equatorialToHorizon();
        double ex = h[6];
        double ey = h[7];
        double ez = h[8];
        double ra = Math.toDegrees(Math.atan2(ey, ex));
        double dec = Math.toDegrees(Math.asin(ez));
        sky.setCatalogue(new StarCatalogue("test", 1, new double[] {ra < 0 ? ra + 360 : ra}, new double[] {dec},
            new float[1], new float[1], new float[] {1}, new float[] {0.6f}, new int[1], new String[1],
            new String[1]));
        sky.update(jd);
        double fov = 4;
        look(0, 1, 0, fov);
        rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), -4f);
        rig.frames(sky, 3);
        double with = illuminance(rig.read(), fov);
        rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Boolean>setting("stars"), false);
        rig.frames(sky, 3);
        double without = illuminance(rig.read(), fov);
        double[] t = new double[3];
        rig.atmosphere.model().transmittanceToTop(rig.atmosphere.model().bottomRadius() + 0.0005, 1, 40, t);
        double expected = sky.stars().illuminance()[0] * rig.atmosphere.sceneUnits()
            * (0.2126 * t[0] + 0.7152 * t[1] + 0.0722 * t[2]);
        System.out.printf(Locale.ROOT, "a star at the zenith: %.4g scene units of illuminance, expected %.4g; the sky "
            + "about it %.4g%n", with - without, expected, without);
        assertThat(with - without).isCloseTo(expected, within(0.05 * expected));
        // A moonless night's sky: airglow, the Milky Way, starlight: dark, not black
        assertThat(without).isPositive();
    }

    @Test
    void theMoonsDiscGivesItsIlluminanceAndIsLitTowardsTheSun() {
        double jd = find(e -> e.sun().altitude() < -18 && e.moon().altitude() > 35
            && Math.abs(e.moonPhase().illuminatedFraction() - 0.5) < 0.1);
        NightSky sky = new NightSky(SkySettings.forQuality(Quality.HIGH, VIENNA));
        sky.update(jd);
        Ephemeris e = sky.ephemeris();
        double[] m = {e.moon().east(), e.moon().up(), -e.moon().north()};
        double fov = 1.2;
        look(m[0], m[1], m[2], fov);
        rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), 4f);
        rig.frames(sky, 3);
        float[][] rgb = rig.read();
        double disc = illuminance(rgb, fov);
        double[] t = new double[3];
        rig.atmosphere.transmittance(1, 0, t);
        double expected = e.moonIlluminance() * rig.atmosphere.sceneUnits() * (0.2126 * t[0] + 0.7152 * t[1]
            + 0.0722 * t[2]);
        System.out.printf(Locale.ROOT, "the moon (%.0f %% lit, %.1f degrees up): %.4g scene units, expected %.4g%n",
            100 * e.moonPhase().illuminatedFraction(), e.moon().altitude(), disc, expected);
        assertThat(disc).isCloseTo(expected, within(0.1 * expected));
        // Lit on the side towards the sun, dark on the other: the sun's direction projected on the image
        double[] s = {e.sun().east(), e.sun().up(), -e.sun().north()};
        double[] right = normalize(cross(m, new double[] {0, 1, 0}));
        double[] up = cross(right, m);
        double sx = dot(s, right);
        double sy = dot(s, up);
        double l = Math.hypot(sx, sy);
        double radius = Math.toDegrees(rig.atmosphere.lightRadius(1)) / fov * SIZE;
        int litX = (int) Math.round(SIZE / 2.0 - 0.5 + 0.6 * radius * sx / l);
        int litY = (int) Math.round(SIZE / 2.0 - 0.5 - 0.6 * radius * sy / l);
        int darkX = (int) Math.round(SIZE / 2.0 - 0.5 - 0.6 * radius * sx / l);
        int darkY = (int) Math.round(SIZE / 2.0 - 0.5 + 0.6 * radius * sy / l);
        double lit = rig.pixel(rgb, litX, litY)[1];
        double dark = rig.pixel(rgb, darkX, darkY)[1];
        System.out.printf(Locale.ROOT, "towards the sun %.4g, away %.4g%n", lit, dark);
        assertThat(lit).isGreaterThan(20 * dark);
    }

    @Test
    void byDayThereAreNoStars() {
        double jd = find(e -> e.sun().altitude() > 30);
        NightSky sky = new NightSky(SkySettings.forQuality(Quality.HIGH, VIENNA));
        sky.update(jd);
        look(0, 1, 0, 4);
        rig.frames(sky, 2);
        assertThat(rig.atmosphere.starCount()).isZero();
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] normalize(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[] {a[0] / l, a[1] / l, a[2] / l};
    }
}
