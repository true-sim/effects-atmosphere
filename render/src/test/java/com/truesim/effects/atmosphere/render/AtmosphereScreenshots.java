package com.truesim.effects.atmosphere.render;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import com.truesim.effects.atmosphere.ephemeris.Ephemeris;
import com.truesim.effects.atmosphere.ephemeris.EphemerisConfig;
import com.truesim.engine.vulkan.Gpu;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Pictures and a film of the atmosphere over a landscape ({@link AtmosphereScene}: Graz, the scene's sky components,
 * the bundled star catalogue), at the HIGH preset, exposed automatically ({@link AtmosphereExposure}) and shown
 * through the engine's display transform (ACES): noon, sunset, the earth's shadow, twilight, a moonlit and a moonless
 * night from the ground; the sky from 10 km up and from orbit (400 km); and {@code frames/} of a time-lapse from before
 * sunrise to night (encode: {@code ffmpeg -framerate 30 -i frames/f%04d.png -c:v libx264 -pix_fmt yuv420p -crf 20
 * timelapse.mp4}).
 *
 * <p>Into {@code TRUESIM_ATMOSPHERE_SHOTS} (or {@code build/screenshots/atmosphere}); run with
 * {@code TRUESIM_SCREENSHOTS=true ./gradlew :atmosphere-render:test --tests '*AtmosphereScreenshots'}.
 * {@code TRUESIM_ATMOSPHERE_FILM=false} leaves the film out.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class AtmosphereScreenshots {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double FPS = 30;

    @Test
    void renderTheSky() throws Exception {
        String dir = System.getenv("TRUESIM_ATMOSPHERE_SHOTS");
        Path out = Files.createDirectories(dir != null ? Path.of(dir) : Path.of("build", "screenshots", "atmosphere"));
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("atmosphere screenshots")
                 .surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(gpu(instance));
             AtmosphereScene s = new AtmosphereScene(device, WIDTH, HEIGHT, Quality.HIGH, true)) {
            double summer = AstroTime.julianDay(2026, 6, 21, 0, 0, 0);
            s.at(summer + 11.0 / 24);
            s.look(0, 2, 0, 40, 4, 70);
            s.load();
            shot(s, out, "01-noon.png", summer + 11.0 / 24, 0, 2, 0, 40, 4, 70);
            double sunset = sunAt(summer + 0.5, 1.5, false);
            shot(s, out, "02-sunset.png", sunset, 0, 2, 0, sunAzimuth(sunset), 3, 70);
            double belt = sunAt(summer + 0.5, -2.5, false);
            shot(s, out, "03-earth-shadow.png", belt, 0, 1500, 0, sunAzimuth(belt) + 180, 4, 80);
            double twilight = sunAt(summer + 0.5, -6, false);
            shot(s, out, "04-twilight.png", twilight, 0, 2, 0, sunAzimuth(twilight), 8, 80);
            double moonlit = moonlitNight();
            shot(s, out, "05-night-moon.png", moonlit, 0, 2, 0, moonAzimuth(moonlit), moonAltitude(moonlit) - 8, 80);
            double dark = moonlessNight();
            shot(s, out, "06-night-stars.png", dark, 0, 2, 0, 180, 40, 100);
            double afternoon = summer + 15.0 / 24;
            shot(s, out, "07-altitude-10km.png", afternoon, 0, 10_000, 0, sunAzimuth(afternoon) + 120, -2, 75);
            double evening = sunAt(summer + 0.5, 3, false);
            shot(s, out, "08-orbit-400km-terminator.png", evening, 0, 400_000, 0, sunAzimuth(evening) + 180, -16, 75);
            shot(s, out, "09-orbit-400km-day.png", afternoon, 0, 400_000, 0, sunAzimuth(afternoon) + 90, -16, 75);
            // Morning fog with the low sun's shafts through it, the house and rocks casting them
            double morning = sunAt(summer, 6, true);
            s.fog(0.012f, 0.03f);
            shot(s, out, "10-fog-shafts.png", morning, 4, 2, 6, sunAzimuth(morning) - 25, 4, 70);
            s.fog(0, 0.03f);
            if (!"false".equals(System.getenv("TRUESIM_ATMOSPHERE_FILM"))) {
                film(s, Files.createDirectories(out.resolve("frames")));
            }
        }
    }

    private static Gpu gpu(VulkanInstance instance) {
        Gpu gpu = instance.bestGpu();
        System.out.println("atmosphere screenshots on " + gpu.name());
        return gpu;
    }

    /** A still: the sky at {@code jd}, the camera there, exposed automatically, written as {@code name}. */
    private static void shot(AtmosphereScene s, Path out, String name, double jd, double x, double y, double z,
        double azimuth, double elevation, double fov) throws Exception {
        s.at(jd);
        s.look(x, y, z, azimuth, elevation, fov);
        s.settle(3);
        for (int i = 0; i < 4; i++) {
            expose(s, s.logAverageLuminance());
            s.settle(3);
        }
        s.write(out.resolve(name));
        System.out.printf(Locale.ROOT, "%s: sun %.2f deg, EV100 %.2f, log-average %.4g cd/m^2, captures %d%n", name,
            s.atmosphere.sunAltitude(), s.atmosphere.config().ev100(), s.logAverageLuminance(), s.capture.captures());
    }

    private static void expose(AtmosphereScene s, double luminance) {
        s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), (float) AtmosphereExposure.ev100(
            luminance));
    }

    /**
     * The film: from 40 minutes before sunrise to the end of nautical twilight on 2026-09-21 (frames spaced so the sun
     * moves slowly near the horizon and fast at midday), the camera turning with the sun, exposure adapting from
     * each frame's luminance with a time constant of half a second.
     */
    private static void film(AtmosphereScene s, Path frames) throws Exception {
        double day = AstroTime.julianDay(2026, 9, 21, 0, 0, 0);
        double rise = sunAt(day, -6, true);
        double end = moonOrNight(day);
        int count = (int) Math.round(28 * FPS);
        double[] times = paced(rise, end, count);
        double ev = s.atmosphere.config().ev100();
        long start = System.nanoTime();
        for (int f = 0; f < count; f++) {
            double jd = times[f];
            s.at(jd);
            double azimuth = sunAzimuth(jd) - 30;
            s.look(0, 2, 0, azimuth, 5, 75);
            s.step();
            s.device.waitIdle();
            double target = AtmosphereExposure.ev100(s.logAverageLuminance());
            ev = f == 0 ? target : AtmosphereExposure.adapt(ev, target, 1 / FPS, 0.5);
            s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), (float) ev);
            s.write(frames.resolve(String.format(Locale.ROOT, "f%04d.png", f)));
        }
        System.out.printf(Locale.ROOT, "film: %d frames in %.1f s, %d captures%n", count,
            (System.nanoTime() - start) / 1e9, s.capture.captures());
    }

    /** {@code count} times from {@code from} to {@code to}, closer together while the sun is low. */
    private static double[] paced(double from, double to, int count) {
        int steps = 4000;
        double[] weight = new double[steps];
        double total = 0;
        for (int i = 0; i < steps; i++) {
            double jd = from + (to - from) * (i + 0.5) / steps;
            double altitude = Math.abs(sun(jd).sun().altitude());
            weight[i] = 1 / (1 + altitude / 4);
            total += weight[i];
        }
        double[] times = new double[count];
        double sum = 0;
        int i = 0;
        for (int f = 0; f < count; f++) {
            double wanted = total * f / (count - 1);
            while (i < steps - 1 && sum + weight[i] < wanted) {
                sum += weight[i];
                i++;
            }
            double frac = weight[i] > 0 ? (wanted - sum) / weight[i] : 0;
            times[f] = from + (to - from) * (i + Math.max(0, Math.min(1, frac))) / steps;
        }
        return times;
    }

    private static Ephemeris sun(double jd) {
        Ephemeris e = new Ephemeris(EphemerisConfig.defaults());
        e.setObserver(AtmosphereScene.GRAZ);
        e.update(jd);
        return e;
    }

    private static double sunAzimuth(double jd) {
        return sun(jd).sun().azimuth();
    }

    private static double moonAzimuth(double jd) {
        return sun(jd).moon().azimuth();
    }

    private static double moonAltitude(double jd) {
        return sun(jd).moon().altitude();
    }

    /** The UT Julian day after {@code from} when the sun's altitude crosses {@code altitude} rising or setting. */
    private static double sunAt(double from, double altitude, boolean rising) {
        double step = 1.0 / 1440;
        double previous = sun(from).sun().altitude();
        for (double jd = from + step; jd < from + 1; jd += step) {
            double now = sun(jd).sun().altitude();
            if (rising ? previous < altitude && now >= altitude : previous > altitude && now <= altitude) {
                return jd - step * (now - altitude) / (now - previous);
            }
            previous = now;
        }
        throw new IllegalStateException("The sun does not cross " + altitude);
    }

    /** A night of autumn 2026 with the sun below -18 degrees and a bright moon 25-40 degrees up. */
    private static double moonlitNight() {
        double from = AstroTime.julianDay(2026, 9, 1, 18, 0, 0);
        for (double jd = from; jd < from + 120; jd += 1.0 / 96) {
            Ephemeris e = sun(jd);
            if (e.sun().altitude() < -18 && e.moon().altitude() > 25 && e.moon().altitude() < 40
                && e.moonPhase().illuminatedFraction() > 0.55 && e.moonPhase().illuminatedFraction() < 0.85) {
                return jd;
            }
        }
        throw new IllegalStateException("No moonlit night");
    }

    /** A night with the sun below -20 degrees and the moon below the horizon, the Milky Way's summer side up. */
    private static double moonlessNight() {
        double from = AstroTime.julianDay(2026, 8, 1, 20, 0, 0);
        for (double jd = from; jd < from + 120; jd += 1.0 / 96) {
            Ephemeris e = sun(jd);
            if (e.sun().altitude() < -20 && e.moon().altitude() < -10) {
                return jd;
            }
        }
        throw new IllegalStateException("No moonless night");
    }

    /** The end of the film's day: the sun at -15 degrees after setting. */
    private static double moonOrNight(double day) {
        return sunAt(day + 0.5, -15, false);
    }
}
