package com.truesim.effects.atmosphere.render;

import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AtmosphereSmokeTest {

    static final int SIZE = 16;

    @Test
    void draws() {
        try (TestGpu gpu = TestGpu.open(); AtmosphereRig rig = new AtmosphereRig(gpu.device(), SIZE, SIZE)) {
            double elevation = Math.toRadians(30);
            double sx = 0;
            double sy = Math.sin(elevation);
            double sz = -Math.cos(elevation);
            rig.atmosphere.fixedSun(sx, sy, sz, 120_000);
            String steps = System.getProperty("steps", System.getenv().getOrDefault("ATMO_STEPS", ""));
            if (!steps.isEmpty()) {
                rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Integer>setting("maxSteps"), Integer.parseInt(steps));
                rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Integer>setting("minSteps"), Integer.parseInt(steps));
            }
            ReferenceAtmosphere reference = new ReferenceAtmosphere(new AtmosphereModel(AtmosphereConfig.EARTH));
            if ("1".equals(System.getenv("ATMO_SINGLE"))) {
                reference.order(1);
                rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Boolean>setting("multipleScattering"), false);
                rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("groundAlbedo"), 0f);
            }
            double[][] dirs = {{0, 1, 0.0001}, {1, Math.tan(Math.toRadians(5)), 0}, {0, Math.tan(Math.toRadians(5)), -1},
                {0, Math.tan(Math.toRadians(20)), 1}};
            for (double[] d : dirs) {
                double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                double dx = d[0] / l;
                double dy = d[1] / l;
                double dz = d[2] / l;
                double ux = Math.abs(dy) > 0.9 ? 0 : 0;
                double uy = Math.abs(dy) > 0.9 ? 0 : 1;
                double uz = Math.abs(dy) > 0.9 ? -1 : 0;
                rig.camera.position(0, 0, 0).set(dx, dy, dz, ux, uy, uz, Math.toRadians(4), SIZE, SIZE, 0.1);
                rig.frames(null, 3);
                float[][] rgb = rig.read();
                double[] e = new double[3];
                rig.atmosphere.lightIlluminance(0, e);
                double[] gpuValue = rig.pixel(rgb, SIZE / 2, SIZE / 2);
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "dir %.3f %.3f %.3f:", dx, dy, dz));
                for (int c = 0; c < 3; c++) {
                    double g = gpuValue[c] / e[c];
                    double ref = reference.radiance(6360.0005, dx, dy, dz, sx, sy, sz, c, Integer.parseInt(System.getenv().getOrDefault("ATMO_PATHS", "20000")), 7 + c);
                    line.append(String.format(Locale.ROOT, "  c%d gpu %.5f ref %.5f (%.1f %%)", c, g, ref,
                        100 * (g - ref) / ref));
                }
                System.out.println(line);
            }
        }
    }
}
