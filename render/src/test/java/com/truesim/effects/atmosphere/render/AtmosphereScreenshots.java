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
import java.util.List;
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
            if (!"false".equals(System.getenv("TRUESIM_ATMOSPHERE_STILLS"))) {
                stills(s, out, summer);
            }
            String wanted = System.getenv().getOrDefault("TRUESIM_ATMOSPHERE_FILMS",
                "timelapse,sunset,aerial,ascent,moonrise,shafts,window,wall");
            List<String> films = List.of(wanted.split(","));
            if (films.contains("timelapse")) {
                film(s, Files.createDirectories(out.resolve("frames")));
            }
            if (films.contains("sunset")) {
                // The sun from 8 degrees up to 12 below, seen setting: the sky's colour through sunset and twilight
                double from = sunAt(summer + 0.5, 8, false);
                double to = sunAt(summer + 0.5, -12, false);
                double azimuth = sunAzimuth(sunAt(summer + 0.5, 0, false));
                film(s, out.resolve("film-sunset-twilight"), 300, (scene, t) -> {
                    scene.at(from + (to - from) * t);
                    scene.look(0, 2, 0, azimuth, 6, 75);
                }, Double.NaN);
            }
            if (films.contains("aerial")) {
                // The mountains 10-30 km away from 300 m up, the sun crossing the sky from morning to evening
                double from = summer + 6.0 / 24;
                double to = summer + 18.5 / 24;
                film(s, out.resolve("film-aerial-perspective"), 300, (scene, t) -> {
                    scene.at(from + (to - from) * t);
                    scene.look(0, 300, 0, 200, -1, 60);
                }, Double.NaN);
            }
            if (films.contains("ascent")) {
                // From the ground to orbit (2 m to 400 km) in the afternoon, the view dipping with the horizon
                double jd = summer + 15.0 / 24;
                double azimuth = sunAzimuth(jd) + 100;
                film(s, out.resolve("film-ascent-to-orbit"), 360, (scene, t) -> {
                    double h = 2 * Math.pow(200_000, t);
                    double dip = Math.toDegrees(Math.acos(6360 / (6360 + h / 1000)));
                    scene.at(jd);
                    scene.look(0, h, 0, azimuth, 3 - dip, 75);
                }, Double.NaN);
            }
            if (films.contains("moonrise")) {
                // A dark night: the moon rising in the east through the stars
                double rise = moonrise();
                double azimuth = moonAzimuth(rise + 40.0 / 1440);
                film(s, out.resolve("film-moonrise"), 300, (scene, t) -> {
                    scene.at(rise - 10.0 / 1440 + t * 100.0 / 1440);
                    scene.look(0, 2, 0, azimuth, 9, 60);
                }, Double.NaN);
            }
            if (films.contains("shafts")) {
                // Morning fog, the low sun climbing behind rocks: volumetric fog's light shafts moving
                double from = sunAt(summer, 2, true);
                double to = sunAt(summer, 9, true);
                double azimuth = sunAzimuth(from + (to - from) / 2) - 20;
                s.fog(0.012f, 0.03f);
                film(s, out.resolve("film-fog-shafts"), 300, (scene, t) -> {
                    scene.at(from + (to - from) * t);
                    scene.look(4, 2, 6, azimuth, 4, 70);
                }, Double.NaN);
                s.fog(0, 0.03f);
            }
            if (films.contains("window")) {
                // Inside the room: the morning sun through its east window, shafts in the room's dusty air (its indoor
                // zone) and the fog outside, the sun climbing and turning
                double from = sunAt(summer, 4, true);
                double to = sunAt(summer, 16, true);
                double floor = AtmosphereScene.roomFloor();
                s.fog(0.006f, 0.03f);
                film(s, out.resolve("film-window-shafts"), 300, (scene, t) -> {
                    scene.at(from + (to - from) * t);
                    scene.look(AtmosphereScene.ROOM_X - 1, floor + 1.7, AtmosphereScene.ROOM_Z - 2.3, 160, -12, 100);
                }, Double.NaN);
                s.fog(0, 0.03f);
            }
            if (films.contains("wall")) {
                // Close up: the sun's patch on the room's wall as the sun climbs from 7 to 9 degrees, slowly (its
                // edges' penumbra, and whether they step or crawl as the shadow map's texels turn with the sun)
                double from = sunAt(summer, 7, true);
                double to = sunAt(summer, 9, true);
                double[] patch = windowPatch(from + (to - from) / 2);
                double x = AtmosphereScene.ROOM_X + 0.3;
                double y = AtmosphereScene.roomFloor() + 1.3;
                double z = AtmosphereScene.ROOM_Z - 0.2;
                double azimuth = Math.toDegrees(Math.atan2(patch[0] - x, z - patch[2]));
                double elevation = Math.toDegrees(Math.atan2(patch[1] - y, Math.hypot(patch[0] - x, patch[2] - z)));
                s.fog(0.006f, 0.03f);
                s.at(from);
                s.look(x, y, z, azimuth, elevation, 44);
                s.settle(3);
                film(s, out.resolve("film-wall-patch"), 300, (scene, t) -> {
                    scene.at(from + (to - from) * t);
                    scene.look(x, y, z, azimuth, elevation, 44);
                }, AtmosphereExposure.ev100(s.logAverageLuminance()));
                s.fog(0, 0.03f);
            }
        }
    }

    /**
     * Where the sun at {@code jd} lights the room's far walls through the middle of its window (1.6 m up in the east
     * wall): the world position on the south or west wall.
     */
    private static double[] windowPatch(double jd) {
        Ephemeris e = sun(jd);
        double altitude = Math.toRadians(e.sun().altitude());
        double azimuth = Math.toRadians(e.sun().azimuth());
        double sx = Math.sin(azimuth) * Math.cos(altitude); // towards the sun
        double sy = Math.sin(altitude);
        double sz = -Math.cos(azimuth) * Math.cos(altitude);
        double toWest = 6 / sx; // from the window (x = 3) to the west wall (x = -3)
        double toSouth = sz < 0 ? 2.5 / -sz : Double.MAX_VALUE;
        double t = Math.min(toWest, toSouth);
        return new double[] {AtmosphereScene.ROOM_X + 3 - sx * t, AtmosphereScene.roomFloor() + 1.6 - sy * t,
            AtmosphereScene.ROOM_Z - sz * t};
    }

    /** The stills. */
    private static void stills(AtmosphereScene s, Path out, double summer) throws Exception {
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
        // From orbit the lit planet sets the exposure (a sunny ground's EV), whatever share of the view is space
        shot(s, out, "08-orbit-400km-terminator.png", evening, 0, 400_000, 0, sunAzimuth(evening) + 180, -16, 75, 13);
        shot(s, out, "09-orbit-400km-day.png", afternoon, 0, 400_000, 0, sunAzimuth(afternoon) + 90, -16, 75, 13);
        double morning = sunAt(summer, 6, true);
        s.fog(0.012f, 0.03f);
        shot(s, out, "10-fog-shafts.png", morning, 4, 2, 6, sunAzimuth(morning) - 25, 4, 70);
        double window = sunAt(summer, 8, true);
        s.fog(0.006f, 0.03f);
        shot(s, out, "11-window-shafts.png", window, AtmosphereScene.ROOM_X - 1, AtmosphereScene.roomFloor() + 1.7,
            AtmosphereScene.ROOM_Z - 2.3, 160, -12, 100);
        s.fog(0, 0.03f);
    }

    /** What a film's frame at {@code t} (0 to 1 over the film) shows: the sky's time, the camera. */
    private interface Scene {
        void at(AtmosphereScene s, double t);
    }

    /**
     * A film of {@code count} frames at 30 a second into {@code dir} ({@code f%04d.png}): exposed automatically (each
     * frame's log-average luminance, eased with a time constant of half a second), or at {@code ev100}. Prints the
     * largest frame-to-frame change of the displayed image's mean (second difference, of 255): flicker.
     */
    private static void film(AtmosphereScene s, Path dir, int count, Scene scene, double ev100) throws Exception {
        Files.createDirectories(dir);
        double ev = s.atmosphere.config().ev100();
        scene.at(s, 0);
        for (int i = 0; i < 20; i++) { // the exposure settled on the first frame first
            s.step();
            s.device.waitIdle();
            ev = Double.isNaN(ev100) ? AtmosphereExposure.ev100(s.logAverageLuminance()) : ev100;
            s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), (float) ev);
        }
        double[] means = new double[count];
        long start = System.nanoTime();
        int captures = s.capture.captures();
        for (int f = 0; f < count; f++) {
            scene.at(s, f / (count - 1.0));
            s.step();
            s.device.waitIdle();
            if (Double.isNaN(ev100)) {
                ev = AtmosphereExposure.adapt(ev, AtmosphereExposure.ev100(s.logAverageLuminance()), 1 / FPS, 0.5);
                s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), (float) ev);
            }
            s.write(dir.resolve(String.format(Locale.ROOT, "f%04d.png", f)));
            means[f] = meanLuma(s.displayed());
        }
        double flicker = 0;
        int worst = 0;
        for (int f = 1; f + 1 < count; f++) {
            double d = Math.abs(means[f + 1] - 2 * means[f] + means[f - 1]) * 255;
            if (d > flicker) {
                flicker = d;
                worst = f;
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d frames in %.1f s, %d captures; largest second difference of the mean "
            + "%.2f of 255 (frame %d)%n", dir.getFileName(), count, (System.nanoTime() - start) / 1e9,
            s.capture.captures() - captures, flicker, worst);
    }

    private static double meanLuma(float[][] rgb) {
        double sum = 0;
        for (int i = 0; i < rgb[0].length; i++) {
            sum += 0.2126 * clamp(rgb[0][i]) + 0.7152 * clamp(rgb[1][i]) + 0.0722 * clamp(rgb[2][i]);
        }
        return sum / rgb[0].length;
    }

    private static double clamp(float v) {
        return Float.isFinite(v) ? Math.max(0, Math.min(1, v)) : 1;
    }

    /** A night of autumn 2026 when the moon rises, gibbous, with the sun below -20 degrees for the next 100 minutes. */
    private static double moonrise() {
        double from = AstroTime.julianDay(2026, 9, 1, 0, 0, 0);
        double step = 1.0 / 1440;
        double previous = sun(from).moon().altitude();
        for (double jd = from + step; jd < from + 120; jd += step) {
            Ephemeris e = sun(jd);
            double now = e.moon().altitude();
            if (previous < 0 && now >= 0 && e.sun().altitude() < -20 && sun(jd + 100.0 / 1440).sun().altitude() < -18
                && e.moonPhase().illuminatedFraction() > 0.6) {
                return jd;
            }
            previous = now;
        }
        throw new IllegalStateException("No moonrise");
    }

    private static Gpu gpu(VulkanInstance instance) {
        Gpu gpu = instance.bestGpu();
        System.out.println("atmosphere screenshots on " + gpu.name());
        return gpu;
    }

    /** A still: the sky at {@code jd}, the camera there, exposed automatically, written as {@code name}. */
    private static void shot(AtmosphereScene s, Path out, String name, double jd, double x, double y, double z,
        double azimuth, double elevation, double fov) throws Exception {
        shot(s, out, name, jd, x, y, z, azimuth, elevation, fov, Double.NaN);
    }

    /** A still exposed at {@code ev100} (NaN: automatically). */
    private static void shot(AtmosphereScene s, Path out, String name, double jd, double x, double y, double z,
        double azimuth, double elevation, double fov, double ev100) throws Exception {
        s.at(jd);
        s.look(x, y, z, azimuth, elevation, fov);
        s.settle(3);
        for (int i = 0; i < 4; i++) {
            if (Double.isNaN(ev100)) {
                expose(s, s.logAverageLuminance());
            } else {
                s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), (float) ev100);
            }
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
     * The film: from civil dawn (the sun 6 degrees below) to a starry night on 2026-09-21 (frames spaced so the sun
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

    /** The end of the film's day: the sun at -21 degrees after setting, a starry night. */
    private static double moonOrNight(double day) {
        return sunAt(day + 0.5, -21, false);
    }
}
