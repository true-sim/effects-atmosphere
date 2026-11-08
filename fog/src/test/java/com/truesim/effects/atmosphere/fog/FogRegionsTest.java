package com.truesim.effects.atmosphere.fog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.fog.hooks.FogConstants;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** The scene's fog regions in world terms: placed, turned and scaled by their nodes, hidden nodes left out. */
class FogRegionsTest {

    @Test
    void regionsFollowTheirNodesWorldTransforms() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        FogRegions regions = new FogRegions(scene);
        long parent = scene.create();
        scene.setTranslation(parent, 10, 0, 0);
        long node = scene.create(parent);
        scene.setTranslation(node, 0, 2, 0);
        // 90 degrees about y: the node's x axis along the world's -z
        scene.setRotation(node, 0, Math.sqrt(0.5), 0, Math.sqrt(0.5));
        scene.setScale(node, 2, 1, 3);
        scene.components().set(node, FogVolume.TYPE, new FogVolume(FogShape.ELLIPSOID, new Vec3(1, 2, 3), 0.2f,
            new Vec3(0.5, 0.6, 0.7), 0.1f, 0.5f));
        scene.update();
        regions.update();
        assertThat(regions.count()).isEqualTo(1);
        assertThat(regions.zone(0)).isFalse();
        assertThat(regions.kind(0)).isEqualTo(FogConstants.FOG_ELLIPSOID | FogConstants.FOG_VOLUME << 4);
        assertThat(regions.center(0, 0)).isCloseTo(10, within(1e-9));
        assertThat(regions.center(0, 1)).isCloseTo(2, within(1e-9));
        assertThat(regions.axis(0, 0, 2)).isCloseTo(-1, within(1e-9));
        assertThat(regions.axis(0, 2, 0)).isCloseTo(1, within(1e-9));
        assertThat(regions.axis(0, 1, 1)).isCloseTo(1, within(1e-9));
        assertThat(regions.halfExtent(0, 0)).isCloseTo(2, within(1e-9));
        assertThat(regions.halfExtent(0, 2)).isCloseTo(9, within(1e-9));
        assertThat(regions.boundingRadius(0)).isCloseTo(Math.sqrt(4 + 4 + 81), within(1e-9));
        assertThat(regions.density(0)).isEqualTo(0.2f);
        assertThat(regions.falloff(0)).isEqualTo(0.1f);
        assertThat(regions.blendDistance(0)).isEqualTo(0.5f);
        assertThat(regions.light(0)).isEqualTo(1);
        assertThat(regions.albedo(0, 2)).isEqualTo(0.7f);
        assertThat(regions.distance(0, 10, 2, 0)).isZero();
        assertThat(regions.distance(0, 10, 102, 0)).isCloseTo(100 - Math.sqrt(89), within(1e-9));
    }

    @Test
    void zonesAreTheirModesAndHiddenNodesHaveNone() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        FogRegions regions = new FogRegions(scene);
        long room = scene.create();
        long hall = scene.create();
        long hidden = scene.create();
        scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(4, 2, 3)));
        scene.components().set(hall, IndoorZone.TYPE, IndoorZone.box(new Vec3(4, 2, 3)).replacing(0.05f,
            new Vec3(0.3, 0.3, 0.3)).withLight(0.5f).withBlendDistance(1).withShape(FogShape.ELLIPSOID));
        scene.components().set(hidden, IndoorZone.TYPE, IndoorZone.box(new Vec3(1, 1, 1)));
        scene.setLayers(hidden, 0);
        scene.update();
        regions.update();
        assertThat(regions.count()).isEqualTo(2);
        assertThat(regions.kind(0)).isEqualTo(FogConstants.FOG_BOX | FogConstants.FOG_EXCLUDE << 4);
        assertThat(regions.kind(1)).isEqualTo(FogConstants.FOG_ELLIPSOID | FogConstants.FOG_REPLACE << 4);
        assertThat(regions.zone(1)).isTrue();
        assertThat(regions.density(0)).isZero();
        assertThat(regions.density(1)).isEqualTo(0.05f);
        assertThat(regions.light(1)).isEqualTo(0.5f);
        assertThat(regions.blendDistance(1)).isEqualTo(1);
        assertThat(regions.albedo(1, 0)).isEqualTo(0.3f);
    }

    @Test
    void manyRegionsGrowTheArrays() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        FogRegions regions = new FogRegions(scene);
        for (int i = 0; i < 40; i++) {
            long node = scene.create();
            scene.setTranslation(node, i, 0, 0);
            scene.components().set(node, FogVolume.TYPE, FogVolume.box(new Vec3(1, 1, 1), 0.1f).withFalloff(0.2f)
                .withBlendDistance(0.1f));
        }
        scene.update();
        regions.update();
        assertThat(regions.count()).isEqualTo(40);
        assertThat(regions.center(39, 0)).isEqualTo(39);
    }

    @Test
    void componentsRefuseWhatIsNoRegion() {
        Vec3 one = new Vec3(1, 1, 1);
        for (Vec3 size : new Vec3[] {new Vec3(0, 1, 1), new Vec3(1, 0, 1), new Vec3(1, 1, 0)}) {
            assertThatThrownBy(() -> FogVolume.box(size, 0.1f)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> IndoorZone.box(size)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> FogVolume.box(one, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FogVolume.box(one, 1).withBlendDistance(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FogVolume.box(one, 1).withFalloff(Float.NaN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndoorZone.box(one).replacing(-1, one)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndoorZone.box(one).withBlendDistance(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndoorZone.box(one).withLight(-0.5f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndoorZone.box(new Vec3(1, 1, 1)).withLight(2))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
