package com.truesim.effects.atmosphere.render;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * GPU time of the atmosphere's passes per frame at 1920 x 1080 over the landscape ({@link AtmosphereScene}), from the
 * graph's per-pass timestamps: medians of 240 frames after 120 uncounted ones, per scenario: noon with a still sun
 * (the tables kept, no capture), the sun moving every frame (a capture and the environment's filtering every frame),
 * the media changing every frame (the transmittance and multiple-scattering tables made every frame), a starry night,
 * fog in froxels, each preset, and aerial perspective off (what its hook costs the shading). Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :atmosphere-render:test --tests '*AtmosphereBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class AtmosphereBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int WARM = 120;
    private static final int FRAMES = 240;

    @Test
    void passTimes() throws Exception {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("atmosphere benchmark")
                 .surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            System.out.println("atmosphere benchmark on " + instance.bestGpu().name() + ", " + WIDTH + " x " + HEIGHT);
            double summer = AstroTime.julianDay(2026, 6, 21, 0, 0, 0);
            double noon = summer + 11.0 / 24;
            double night = AstroTime.julianDay(2026, 8, 12, 22, 0, 0);
            for (Quality quality : new Quality[] {Quality.HIGH, Quality.LOW, Quality.MEDIUM, Quality.ULTRA}) {
                try (AtmosphereScene s = new AtmosphereScene(device, WIDTH, HEIGHT, quality, true)) {
                    s.graph.timings(true);
                    s.at(noon);
                    s.look(0, 2, 0, 40, 4, 70);
                    s.load();
                    run(s, quality + ", noon, still sun", f -> { });
                    if (quality != Quality.HIGH) {
                        continue;
                    }
                    run(s, "HIGH, the sun moving every frame (capture and filtering every frame)",
                        f -> s.at(noon + f * 2.0 / 1440));
                    s.at(noon);
                    run(s, "HIGH, the media changing every frame (tables made every frame)",
                        f -> s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("groundAlbedo"),
                            f % 2 == 0 ? 0.3f : 0.31f));
                    s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("groundAlbedo"), 0.3f);
                    s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Boolean>setting("aerialPerspective"), false);
                    run(s, "HIGH, noon, aerial perspective off", f -> { });
                    s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Boolean>setting("aerialPerspective"), true);
                    s.fog(0.01f, 0.03f);
                    run(s, "HIGH, noon, fog in froxels (160 x 90 x 64, every light, shadows)", f -> { });
                    s.graph.settings().set(VolumetricFog.SLOT.setting(), "analytic");
                    run(s, "HIGH, noon, fog by the hook alone", f -> { });
                    s.graph.settings().set(VolumetricFog.SLOT.setting(), "froxel");
                    s.fog(0, 0.03f);
                    s.at(night);
                    s.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), -5f);
                    s.look(0, 2, 0, 180, 40, 90);
                    run(s, "HIGH, a starry night (15,598 stars to magnitude 6.5)", f -> { });
                }
            }
        }
    }

    private interface Frame {
        void before(int frame);
    }

    /** Medians per pass of the atmosphere, the fog and the environment, and of the frame, over {@link #FRAMES}. */
    private static void run(AtmosphereScene s, String name, Frame each) {
        Map<String, List<Double>> passes = new LinkedHashMap<>();
        List<Double> totals = new ArrayList<>();
        int builds = s.graph.builds();
        for (int f = 0; f < WARM + FRAMES; f++) {
            each.before(f);
            s.step();
            if (f < WARM) {
                continue;
            }
            GpuTimings t = s.graph.timings();
            if (t == null) {
                continue;
            }
            totals.add(t.totalMillis());
            for (int i = 0; i < t.count(); i++) {
                String pass = t.pass(i);
                if (pass.startsWith("atmosphere") || pass.startsWith("fog") || pass.startsWith("environment")) {
                    String key = pass.startsWith("environment") ? "environment filtering (all its passes)" : pass;
                    passes.computeIfAbsent(key, k -> new ArrayList<>());
                    List<Double> list = passes.get(key);
                    if (pass.startsWith("environment") && list.size() == totals.size()) {
                        list.set(list.size() - 1, list.getLast() + finite(t.millis(i)));
                    } else {
                        list.add(finite(t.millis(i)));
                    }
                }
            }
        }
        s.device.waitIdle();
        System.out.printf(Locale.ROOT, "%n%s (frame: median %.3f ms of GPU; graph builds %d; sun %.1f degrees, %d stars)%n",
            name, median(totals), s.graph.builds() - builds, s.atmosphere.sunAltitude(), s.atmosphere.starCount());
        double ours = 0;
        for (Map.Entry<String, List<Double>> e : passes.entrySet()) {
            double m = median(e.getValue());
            System.out.printf(Locale.ROOT, "  %-48s %.4f ms (max %.4f)%n", e.getKey(), m, max(e.getValue()));
            ours += m;
        }
        System.out.printf(Locale.ROOT, "  %-48s %.4f ms%n", "sum of the medians above", ours);
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a[a.length / 2];
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }
}
