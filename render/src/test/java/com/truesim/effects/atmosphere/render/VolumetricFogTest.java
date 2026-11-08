package com.truesim.effects.atmosphere.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
 * Volumetric fog in froxels over atmosphere-fog's fog: in the open the froxels give what the fog hook's own closed form
 * gives (one integration, the same media), and under a roof casting the sun's shadow the fog scatters less of the
 * sun's light, which the hook alone cannot do (light shafts).
 */
class VolumetricFogTest {

    private static final int WIDTH = 32;
    private static final int HEIGHT = 16;

    private TestGpu gpu;
    private AtmosphereRig rig;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        rig = gpu.closing(new AtmosphereRig(gpu.device(), WIDTH, HEIGHT, true, true));
        double e = Math.toRadians(70);
        rig.atmosphere.fixedSun(0, Math.sin(e), -Math.cos(e), 120_000); // high, ahead: forward scattering
        rig.camera.position(0, 2, 0).set(0, 0, -1, 0, 1, 0, Math.toRadians(20), WIDTH, HEIGHT, 0.1);
        ShadingHookValues.of(gpu.device()).set(AtmosphereFog.HOOK, AtmosphereFogParameters.DEFAULTS
            .withDensity(0.02f).withHeightFalloff(0).withAlbedo(new Vec3(0.9, 0.9, 0.9)), true);
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            ShadingHookValues.of(gpu.device()).remove(AtmosphereFog.HOOK);
            gpu.close();
        }
    }

    /** The middle row's mean green radiance, the fog looked through towards the horizon. */
    private double fogged(String kind) {
        rig.graph.settings().set(VolumetricFog.SLOT.setting(), kind);
        rig.frames(null, 6);
        float[][] rgb = rig.read();
        double sum = 0;
        for (int x = 0; x < WIDTH; x++) {
            sum += rgb[1][(HEIGHT / 2) * WIDTH + x];
        }
        return sum / WIDTH;
    }

    @Test
    void inTheOpenTheFroxelsGiveWhatTheHookGives() {
        double froxels = fogged("froxel");
        double analytic = fogged("analytic");
        System.out.printf(Locale.ROOT, "open fog: froxels %.4f, the hook alone %.4f%n", froxels, analytic);
        assertThat(froxels).isCloseTo(analytic, within(0.06 * analytic));
    }

    @Test
    void aRoofShadowsTheFogUnderItWhereTheHookAloneCannot() {
        double open = fogged("froxel");
        // A roof 60 m wide and deep, 12 m up, over the view: the sun's cascades shade the fog under it
        rig.add(TestMeshes.box(), rig.grey, 0, 14, -25, 30, 0.5f, 30);
        double roofed = fogged("froxel");
        double hookAlone = fogged("analytic");
        System.out.printf(Locale.ROOT, "fog: open %.4f, under a roof with froxels %.4f, the hook alone %.4f%n", open,
            roofed, hookAlone);
        assertThat(roofed).isLessThan(0.8 * hookAlone);
        assertThat(hookAlone).isCloseTo(open, within(0.1 * open));
    }
}
