package com.truesim.effects.atmosphere.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import com.truesim.effects.atmosphere.sky.SkyAssets;
import com.truesim.effects.atmosphere.sky.SkyComponents;
import com.truesim.effects.atmosphere.sky.SkySettings;
import com.truesim.effects.atmosphere.sky.SkyTime;
import com.truesim.effects.atmosphere.sky.StarsConfig;
import com.truesim.engine.render.Lights;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.core.SceneGraph;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The sky from the scene: the sky components on an environment node drive the night sky and the atmosphere, the star
 * catalogue they name loads through the asset cache, and the per-frame update allocates nothing; the sun and the moon
 * become the scene's directional lights.
 */
class AtmosphereSyncTest {

    @Test
    void theScenesSkyComponentsDriveTheAtmosphereAndItsCatalogueLoadsAsAnAsset() throws Exception {
        try (TestGpu gpu = TestGpu.open(); TaskScheduler tasks = new TaskScheduler();
             AssetCache cache = new AssetCache(tasks)) {
            FrameSlots slots = new FrameSlots(1);
            PathAssets paths = new PathAssets(cache);
            SkyAssets.install(paths);
            Atmosphere atmosphere = gpu.closing(new Atmosphere(gpu.device(), slots, new EngineSettings()));
            Lights lights = gpu.closing(new Lights(gpu.device(), 2, 1, slots));
            lights.setCount(2);
            SceneGraph scene = new SceneGraph(new ChangeClock());
            long node = scene.create();
            Observer vienna = new Observer(48.21, 16.37, 200);
            scene.components().set(node, SkyComponents.SKY, SkySettings.forQuality(Quality.MEDIUM, vienna));
            // 22:00 UT on 2026-08-12: night in Vienna; an hour of sky time per game second
            scene.components().set(node, SkyComponents.SKY_TIME, SkyTime.of(Instant.parse("2026-08-12T22:00:00Z"),
                3600));
            AtmosphereSync sync = new AtmosphereSync(scene, node, cache, paths, atmosphere);
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!sync.starsReady() && System.nanoTime() < deadline) {
                tasks.drainCompleted();
                sync.update(0, 0, 2, 0);
                Thread.sleep(5);
            }
            sync.update(0, 0, 2, 0);
            assertThat(sync.starsReady()).as("%s: %s, %s", sync.stars(), cache.state(sync.stars()),
                cache.failure(sync.stars())).isTrue();
            assertThat(sync.sky().stars().count()).isGreaterThan(4000);
            assertThat(atmosphere.sunAltitude()).isLessThan(-10);
            assertThat(atmosphere.starCount()).isEqualTo(sync.sky().stars().count());
            assertThat(atmosphere.originAltitude()).isEqualTo(200);
            double[] sun = new double[3];
            atmosphere.sunDirection(sun);
            assertThat(sun[1]).isCloseTo(sync.sky().ephemeris().sun().up(), within(1e-9));
            assertThat(sun[2]).isCloseTo(-sync.sky().ephemeris().sun().north(), within(1e-9));
            // Ten game seconds later: ten hours of sky, the sun up
            sync.update(10, 0, 2, 0);
            assertThat(atmosphere.sunAltitude()).isPositive();
            assertThat(atmosphere.starCount()).isZero();
            atmosphere.light(lights, 0, 1);
            assertThat(lights.brightness(0)).isPositive();
            double[] d = new double[3];
            lights.direction(0, d);
            assertThat(d[1]).isNegative(); // shining down
            // Each frame allocates nothing, the sky moving
            double[] t = {10};
            assertAllocationFree(() -> {
                sync.update(t[0], 0, 2, 0);
                atmosphere.light(lights, 0, 1);
                t[0] += 0.01;
            });
            // Stars switched off: the catalogue released
            scene.components().set(node, SkyComponents.SKY, SkySettings.forQuality(Quality.MEDIUM, vienna)
                .withStars(StarsConfig.forQuality(Quality.MEDIUM).withSource(StarsConfig.Source.OFF)));
            sync.update(10, 0, 2, 0);
            assertThat(sync.stars()).isNull();
            assertThat(sync.starsReady()).isTrue();
            sync.close();
        }
    }

    @Test
    void aFixedSunNeedsNoSky() {
        try (TestGpu gpu = TestGpu.open()) {
            Atmosphere atmosphere = gpu.closing(new Atmosphere(gpu.device(), new FrameSlots(1),
                new EngineSettings()));
            atmosphere.fixedSun(0, 1, 1, 100_000);
            atmosphere.update(null, 0, 0, 0);
            assertThat(atmosphere.sunAltitude()).isCloseTo(45, within(1e-9));
            double[] light = new double[3];
            atmosphere.lightAtCamera(0, light);
            assertThat(light[1]).isPositive().isLessThan(100_000 * atmosphere.sceneUnits());
            assertThat(atmosphere.starCount()).isZero();
            atmosphere.followSky();
        }
    }
}
