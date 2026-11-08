package com.truesim.effects.atmosphere.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.math.Vec3;
import com.truesim.effects.atmosphere.fog.AtmosphereFog;
import com.truesim.effects.atmosphere.fog.hooks.AtmosphereFogParameters;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Light shafts' edges in the froxels: a slatted roof's shafts slanting through the fog across the view's rays. Each
 * froxel lit at its centre alone (one sample, no history: as before) aliases the shafts into froxel-sized blocks;
 * one jittered sample a frame kept over frames comes to the froxels' means (16 samples each, kept over 96 frames) and
 * stays still once settled.
 */
class VolumetricFogShaftsTest {

    private static final int WIDTH = 192;
    private static final int HEIGHT = 108;

    private TestGpu gpu;
    private AtmosphereRig rig;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        rig = gpu.closing(new AtmosphereRig(gpu.device(), WIDTH, HEIGHT, true, true));
        // The sun 50 degrees up from the right and behind: shafts slanting across the view's rays
        double e = Math.toRadians(50);
        double a = Math.toRadians(35);
        rig.atmosphere.fixedSun(Math.cos(e) * Math.cos(a), Math.sin(e), Math.cos(e) * Math.sin(a), 120_000);
        rig.camera.position(0, 2, 0).set(0, -0.3, -1, 0, 1, 0, Math.toRadians(50), WIDTH, HEIGHT, 0.1);
        ShadingHookValues.of(gpu.device()).set(AtmosphereFog.HOOK, AtmosphereFogParameters.DEFAULTS
            .withDensity(0.02f).withHeightFalloff(0).withAlbedo(new Vec3(0.9, 0.9, 0.9)), true);
        // A slatted roof 9 m up: beams 1.5 m wide, 1.5 m apart, across the view: shafts the view's rays cross
        for (int i = -2; i <= 24; i++) {
            rig.add(TestMeshes.box(), rig.grey, 0, 9, -3 * i, 60, 0.25f, 0.75f);
        }
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            ShadingHookValues.of(gpu.device()).remove(AtmosphereFog.HOOK);
            gpu.close();
        }
    }

    /** The froxels sampled so, after {@code frames} frames: the green channel of the last two frames. */
    private float[][] fog(int samples, float history, int frames) {
        rig.graph.settings().set(VolumetricFog.FROXEL.<Integer>setting("samples"), samples)
            .set(VolumetricFog.FROXEL.<Float>setting("history"), history);
        rig.frames(null, frames - 1);
        float[] before = rig.read()[1].clone();
        rig.frames(null, 1);
        return new float[][] {before, rig.read()[1]};
    }

    /** The mean absolute difference of {@code a} and {@code b} over the view, against {@code b}'s mean. */
    private static double difference(float[] a, float[] b) {
        double sum = 0;
        double mean = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
            mean += b[i];
        }
        return sum / mean;
    }

    @Test
    void shaftEdgesSampledAcrossTheFroxelsAreSmoothAndStill() {
        // The froxels' means: 16 jittered samples each a frame, kept over 96 frames
        float[] reference = fog(16, 0.95f, 96)[1];
        float[] centre = fog(1, 0, 4)[1];      // as before: each froxel at its centre
        float[][] kept = fog(1, 0.9f, 48);     // one jittered sample a frame, kept over frames
        double centreError = difference(centre, reference);
        double keptError = difference(kept[1], reference);
        double flicker = difference(kept[1], kept[0]);
        System.out.printf(Locale.ROOT, "fog off the froxels' means: centres %.4f, kept over frames %.4f; frame to frame "
            + "%.4f%n", centreError, keptError, flicker);
        assertThat(centreError).as("centres alone alias the shafts").isGreaterThan(0.005);
        assertThat(keptError).as("kept over frames, the froxels' means").isLessThan(centreError / 2);
        assertThat(flicker).as("still, once settled").isLessThan(centreError / 4);
    }
}
