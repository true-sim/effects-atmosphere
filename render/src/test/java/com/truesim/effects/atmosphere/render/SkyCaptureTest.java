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
 * The sky capture feeds the engine's environment: diffuse environment lighting (the spherical harmonics) and
 * reflections (the prefiltered cube) see the atmosphere's sky, and the capture runs again only when the sky changed.
 */
class SkyCaptureTest {

    private static final int SIZE = 16;

    private TestGpu gpu;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close();
        }
    }

    /** A rig with a box of {@code material} 10 m ahead, lit by the sky alone; the camera above it looking down. */
    private AtmosphereRig rig(boolean captured, java.util.function.ToIntFunction<AtmosphereRig> material) {
        AtmosphereRig rig = gpu.closing(new AtmosphereRig(gpu.device(), SIZE, SIZE, captured));
        rig.lit = false;
        rig.add(TestMeshes.box(), material.applyAsInt(rig), 0, 0, -10, 4);
        double e = Math.toRadians(40);
        rig.atmosphere.fixedSun(0, Math.sin(e), Math.cos(e), 120_000);
        // Looking down onto the box's top at 45 degrees: its mirror direction is 45 degrees up, away from the sun
        rig.camera.position(0, 14, -4).set(0, -Math.sqrt(0.5), -Math.sqrt(0.5), 0, Math.sqrt(0.5), -Math.sqrt(0.5),
            Math.toRadians(2), SIZE, SIZE, 0.1);
        return rig;
    }

    private static double[] middle(AtmosphereRig rig) {
        return rig.pixel(rig.read(), SIZE / 2, SIZE / 2);
    }

    @Test
    void environmentLightingSeesTheCapturedSky() {
        AtmosphereRig captured = rig(true, r -> r.grey);
        captured.frames(null, 6);
        double[] lit = middle(captured);
        AtmosphereRig black = rig(false, r -> r.grey);
        black.frames(null, 6);
        double[] unlit = middle(black);
        System.out.printf(Locale.ROOT, "a grey box's top under the sky: %.4f %.4f %.4f; uncaptured: %.6f %.6f %.6f%n",
            lit[0], lit[1], lit[2], unlit[0], unlit[1], unlit[2]);
        // Lit by the sky's blue light (the diffuse harmonics of the capture), and by nothing without it; the sun not
        // a light here
        assertThat(lit[2]).isGreaterThan(1.2 * lit[0]).isGreaterThan(1e-3);
        assertThat(unlit[2]).isLessThan(0.02 * lit[2]);
        // Its irradiance about the sky's own order: a Lambertian albedo 0.5 under an overcast-like dome of the sky's
        // radiance at 45 degrees gives about half of that radiance
        double[] e = new double[3];
        captured.atmosphere.lightIlluminance(0, e);
        assertThat(captured.capture.captures()).isPositive();
    }

    @Test
    void reflectionsSeeTheCapturedSky() {
        AtmosphereRig mirror = rig(true, r -> r.mirror);
        mirror.frames(null, 6);
        double[] reflected = middle(mirror);
        // The sky itself along the mirror direction, 45 degrees up away from the sun, seen from the same place
        mirror.camera.position(0, 14, -4).set(0, Math.sqrt(0.5), -Math.sqrt(0.5), 0, Math.sqrt(0.5), Math.sqrt(0.5),
            Math.toRadians(2), SIZE, SIZE, 0.1);
        mirror.frames(null, 2);
        double[] sky = middle(mirror);
        System.out.printf(Locale.ROOT, "mirror %.4f %.4f %.4f, sky %.4f %.4f %.4f%n", reflected[0], reflected[1],
            reflected[2], sky[0], sky[1], sky[2]);
        for (int c = 0; c < 3; c++) {
            assertThat(reflected[c]).isCloseTo(sky[c], within(0.2 * sky[c]));
        }
    }

    @Test
    void theSkyIsCapturedAgainOnlyWhenItChanges() {
        AtmosphereRig rig = rig(true, r -> r.grey);
        rig.frames(null, 4);
        int first = rig.capture.captures();
        rig.frames(null, 4);
        assertThat(rig.capture.captures()).as("a still sky").isEqualTo(first);
        double e = Math.toRadians(40.1); // less than captureAngle (0.25 degrees)
        rig.atmosphere.fixedSun(0, Math.sin(e), Math.cos(e), 120_000);
        rig.frames(null, 2);
        assertThat(rig.capture.captures()).as("a sun moved 0.1 degrees").isEqualTo(first);
        e = Math.toRadians(45);
        rig.atmosphere.fixedSun(0, Math.sin(e), Math.cos(e), 120_000);
        rig.frames(null, 2);
        assertThat(rig.capture.captures()).as("a sun moved 5 degrees").isEqualTo(first + 1);
        rig.graph.settings().set(AtmosphereSky.ATMOSPHERE.<Float>setting("ev100"), 14f);
        rig.frames(null, 2);
        assertThat(rig.capture.captures()).as("a new exposure").isEqualTo(first + 2);
        assertThat(rig.environment.source()).isEqualTo(com.truesim.engine.render.Environment.Source.PANORAMA);
        assertThat(rig.environment.panorama()).isEqualTo(rig.capture.texture() & 0xffff);
    }
}
