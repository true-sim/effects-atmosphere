package com.truesim.effects.atmosphere.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aerial perspective (the media hook reading the volume): a distant object takes the atmosphere between it and the
 * camera, its light times the transmittance plus the light scattered in, as the path-traced atmosphere has them; and
 * not at all with the hook off.
 */
class AerialPerspectiveTest {

    private static final int SIZE = 16;
    private static final double NEAR = 40;
    private static final double FAR = 15_000;

    private TestGpu gpu;
    private AtmosphereRig rig;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        rig = gpu.closing(new AtmosphereRig(gpu.device(), SIZE, SIZE));
        // Two equal boxes facing the sun (behind the camera), one 40 m and one 15 km away, the same size on screen
        place(NEAR, -30);
        place(FAR, 30);
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close();
        }
    }

    private void place(double distance, double azimuth) {
        double a = Math.toRadians(azimuth);
        rig.add(TestMeshes.box(), Math.sin(a) * distance, 2, -Math.cos(a) * distance, (float) (distance / 15));
    }

    /** The radiance at the middle of the box at {@code distance} along {@code azimuth}, per unit sun illuminance. */
    private double[] box(double azimuth, double distance) {
        double a = Math.toRadians(azimuth);
        rig.camera.position(0, 2, 0).set(Math.sin(a), 0, -Math.cos(a), 0, 1, 0, Math.toRadians(2), SIZE, SIZE, 0.1);
        rig.frames(null, 5); // the sky captured, then filtered: the environment lights both alike
        float[][] rgb = rig.read();
        double[] e = new double[3];
        rig.atmosphere.lightIlluminance(0, e);
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            out[c] = rgb[c][(SIZE / 2) * SIZE + SIZE / 2] / e[c];
        }
        return out;
    }

    /** The transmittance along the horizontal ray of {@code km} from 2 m over the ground, ray-marched. */
    private static double transmittance(AtmosphereModel model, double km, int c) {
        int steps = 2000;
        double r = model.bottomRadius() + 0.002;
        double depth = 0;
        for (int i = 0; i < steps; i++) {
            double t = (i + 0.5) * km / steps;
            depth += model.extinction(Math.sqrt(r * r + t * t) - model.bottomRadius(), c) * km / steps;
        }
        return Math.exp(-depth);
    }

    @Test
    void aDistantObjectTakesTheAtmosphereBetweenItAndTheCamera() {
        double e = Math.toRadians(20);
        double[] sun = {0, Math.sin(e), Math.cos(e)};
        rig.atmosphere.fixedSun(sun[0], sun[1], sun[2], 120_000);
        double[] near = box(-30, NEAR);
        double[] far = box(30, FAR);
        AtmosphereModel model = new AtmosphereModel(AtmosphereConfig.EARTH);
        ReferenceAtmosphere reference = new ReferenceAtmosphere(model).limit(FAR / 1000);
        double a = Math.toRadians(30);
        for (int c = 0; c < 3; c++) {
            double t = transmittance(model, FAR / 1000, c);
            double in = reference.radiance(model.bottomRadius() + 0.002, Math.sin(a), 0, -Math.cos(a), sun[0], sun[1],
                sun[2], c, 40_000, 5 + c);
            double expected = near[c] * t + in;
            System.out.printf(Locale.ROOT, "channel %d: near %.5f, far %.5f, expected %.5f (T %.3f, in-scattered "
                + "%.5f)%n", c, near[c], far[c], expected, t, in);
            assertThat(far[c]).isCloseTo(expected, within(0.1 * expected));
        }
        // The far box lost contrast: darker by the transmittance, the sky's light in its place
        assertThat(far[1]).isLessThan(0.97 * near[1]);
    }

    @Test
    void withoutTheHookTheDistantObjectLooksAsTheNearOne() {
        rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Boolean>setting("aerialPerspective"), false);
        double e = Math.toRadians(20);
        rig.atmosphere.fixedSun(0, Math.sin(e), Math.cos(e), 120_000);
        double[] near = box(-30, NEAR);
        double[] far = box(30, FAR);
        for (int c = 0; c < 3; c++) {
            assertThat(far[c]).isCloseTo(near[c], within(0.03 * near[c]));
        }
    }
}
