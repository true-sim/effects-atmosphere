package com.truesim.effects.atmosphere.fog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Vec3;
import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.fog.hooks.AtmosphereFogParameters;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.render.testing.TestRenderer;
import com.truesim.engine.scene.ShadingHookComponent;
import com.truesim.engine.scene.ShadingHookSync;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fog hook on the GPU, against closed-form values (effects-atmosphere#4). Emissive walls on black, no other light,
 * so each pixel is its wall's light times the fog's transmittance along the ray, plus the light the fog scatters:
 * a house's front wall 10 m ahead with a window slit, its back wall 20 m ahead, an indoor zone filling the room
 * between. Seen from outside, the air in front of the window is fogged and the room behind it is not; seen from inside,
 * the room is clear and the fog begins at the window. Fog volumes (an ellipsoid; a box turned and scaled by its node)
 * dim what lies behind them by their density times the length through them. Validated.
 */
class FogVolumesTest {

    private static final int SIZE = 64;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final float FRONT = 0.5f;
    private static final float BACK = 0.8f;
    private static final float SIGMA = 0.05f;

    private TestGpu gpu;
    private VulkanDevice device;
    private TestRenderer rig;
    private Materials materials;
    private SceneGraph scene;
    private FogRegions regions;
    private int quad;
    private int front;
    private int back;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        device = gpu.device();
        rig = gpu.closing(TestRenderer.of(device, SIZE, SIZE));
        quad = rig.add(TestMeshes.quad());
        materials = gpu.closing(new Materials(device, 8));
        front = materials.add(emissive(FRONT));
        back = materials.add(emissive(BACK));
        rig.scene().setMaterials(materials);
        scene = new SceneGraph(new ChangeClock());
        regions = new FogRegions(scene);
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            rig.scene().setMaterials(null);
            gpu.close(); // fails on validation errors
        }
    }

    @Test
    void theListingRebuildsTheEnginesShadingWithTheFog() {
        ShadingHooks hooks = ShadingHooks.of(device);
        assertThat(hooks.hooks()).contains(AtmosphereFog.HOOK);
        assertThat(hooks.parameters(hooks.index(AtmosphereFog.HOOK))).isEqualTo(AtmosphereFogParameters.class);
    }

    @Test
    void fromOutsideTheAirInFrontOfTheWindowIsFoggedAndTheRoomBehindItClear() {
        house();
        long room = room();
        fog(new Vec3(0, 0, 0), new Vec3(0, 0, 0));
        SceneView main = view(0, 0, 0, 0, 0, -1);
        AtmosphereFog fog = new AtmosphereFog(main, regions);
        main.port().add(fog);
        frames();
        assertThat(fog.regionsWritten()).isEqualTo(1);
        // Through the window: the back wall 20 m away, only its first 10 m outdoors
        double window = BACK * Math.exp(-SIGMA * 10);
        assertChannel(SIZE / 2, SIZE / 2, window, "the room's back wall through the window");
        // The front wall beside the window, outdoors all the way
        double[] d = direction(pixel(-0.67), SIZE / 2);
        assertChannel(pixel(-0.67), SIZE / 2, FRONT * Math.exp(-SIGMA * 10 / -d[2]), "the front wall");

        // Without the zone the room's air is fogged too: the window shows the back wall through 20 m of fog
        scene.components().remove(room, IndoorZone.TYPE);
        update();
        frames();
        assertChannel(SIZE / 2, SIZE / 2, BACK * Math.exp(-SIGMA * 20), "the back wall with no indoor zone");
    }

    @Test
    void fromInsideTheRoomIsClearAndTheFogStartsAtTheWindow() {
        house();
        room();
        // An outdoor wall 20 m beyond the window, seen from the middle of the room
        rig.scene().set(2, quad, back, 0, 0, 10, 0, 0, 0, 1, 12, 12, 1);
        // The outdoor fog as content holds it: the hook's values on a node, handed over by the engine's sync
        long weather = scene.create();
        scene.components().set(weather, ShadingHookComponent.TYPE, new ShadingHookComponent(AtmosphereFog.HOOK,
            MaterialData.builder(AtmosphereFog.HOOK).set("density", SIGMA).set("heightFalloff", 0f)
                .set("albedo", 0f, 0f, 0f).set("ambient", 0f, 0f, 0f).set("sunIlluminance", 0f, 0f, 0f).build()
                .values(), true));
        update();
        new ShadingHookSync(scene, values()).update();
        SceneView main = view(0, 0, -15, 0, 0, 1);
        main.port().add(new AtmosphereFog(main, regions));
        frames();
        // 25 m to the outdoor wall: 5 m of clear room, 20 m of fog
        assertChannel(SIZE / 2, SIZE / 2, BACK * Math.exp(-SIGMA * 20), "the outdoor wall through the window");
        // The front wall's inside, 5 m ahead beside the window: clear
        assertChannel(pixel(-0.67), SIZE / 2, FRONT, "the front wall from inside");
    }

    @Test
    void theFogScattersItsLightAlongTheOutdoorPartOfTheRayOnly() {
        house();
        room();
        Vec3 ambient = new Vec3(0.6, 0.6, 0.6);
        fog(new Vec3(1, 1, 1), ambient);
        SceneView main = view(0, 0, 0, 0, 0, -1);
        main.port().add(new AtmosphereFog(main, regions));
        frames();
        double t = Math.exp(-SIGMA * 10);
        assertChannel(SIZE / 2, SIZE / 2, BACK * t + 0.6 * (1 - t), "the back wall and the light of 10 m of fog");
    }

    @Test
    void aZoneReplacingTheFogFillsTheRoomWithItsOwnAir() {
        house();
        long room = room();
        scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(11, 5, 5)).replacing(0.1f,
            new Vec3(0, 0, 0)));
        update();
        fog(new Vec3(0, 0, 0), new Vec3(0, 0, 0));
        SceneView main = view(0, 0, 0, 0, 0, -1);
        main.port().add(new AtmosphereFog(main, regions));
        frames();
        assertChannel(SIZE / 2, SIZE / 2, BACK * Math.exp(-SIGMA * 10 - 0.1 * 10), "10 m of fog, 10 m of the room's air");
    }

    @Test
    void theZonesCapLeavesZonesOutNearestFirst() {
        house();
        room();
        // A second, farther zone that would also clear the outdoor air in front of the house
        long far = scene.create();
        scene.setTranslation(far, 0, 0, -3);
        scene.components().set(far, IndoorZone.TYPE, IndoorZone.box(new Vec3(1, 1, 1)));
        update();
        fog(new Vec3(0, 0, 0), new Vec3(0, 0, 0));
        rig.graph().settings().set(FogSettings.MAX_ZONES, 0);
        SceneView main = view(0, 0, 0, 0, 0, -1);
        AtmosphereFog fog = new AtmosphereFog(main, regions);
        main.port().add(fog);
        frames();
        assertThat(fog.regionsWritten()).isZero();
        assertChannel(SIZE / 2, SIZE / 2, BACK * Math.exp(-SIGMA * 20), "no zone taken: 20 m of fog");
        rig.graph().settings().set(FogSettings.MAX_ZONES, 1);
        frames();
        assertThat(fog.regionsWritten()).isEqualTo(1);
        // The nearer zone (2 to 4 m ahead, its bounds nearer than the room's) clears 2 m; the room stays fogged
        assertChannel(SIZE / 2, SIZE / 2, BACK * Math.exp(-SIGMA * 18), "the nearest zone only");
    }

    @Test
    void regionsBeyondTheCullDistanceAreLeftOutAndTheFogTurnsOffByItsBit() {
        house();
        room();
        fog(new Vec3(0, 0, 0), new Vec3(0, 0, 0));
        rig.graph().settings().set(FogSettings.CULL_DISTANCE, 1f);
        SceneView main = view(0, 0, 0, 0, 0, -1);
        AtmosphereFog fog = new AtmosphereFog(main, regions);
        main.port().add(fog);
        frames();
        assertThat(fog.regionsWritten()).as("the room's bounds are 1.9 m away").isZero();
        assertChannel(SIZE / 2, SIZE / 2, BACK * Math.exp(-SIGMA * 20), "the room left out: 20 m of fog");
        fog.active(false);
        frames();
        assertChannel(SIZE / 2, SIZE / 2, BACK, "the fog off");
    }

    @Test
    void fogVolumesDimWhatLiesBehindThemByTheirDensityAlongTheRay() {
        rig.scene().set(0, quad, back, 0, 0, -20, 0, 0, 0, 1, 12, 12, 1);
        long ball = scene.create();
        scene.setTranslation(ball, 0, 0, -6);
        scene.components().set(ball, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(2, 2, 2), 0.3f)
            .withAlbedo(new Vec3(0, 0, 0)));
        // A unit box scaled to 1 x 1 x 1.5 m half extents, turned 45 degrees about y, 3 m to the right
        long box = scene.create();
        scene.setTranslation(box, 3, 0, -6);
        double s = Math.sin(Math.PI / 8);
        scene.setRotation(box, 0, s, 0, Math.cos(Math.PI / 8));
        scene.setScale(box, 1, 1.5, 1);
        scene.components().set(box, FogVolume.TYPE, FogVolume.box(new Vec3(1, 1, 1), 0.3f)
            .withAlbedo(new Vec3(0, 0, 0)));
        update();
        assertThat(regions.count()).isEqualTo(2);
        values().set(AtmosphereFog.HOOK, fog(new Vec3(0, 0, 0), new Vec3(0, 0, 0)).withDensity(0), true);
        SceneView main = view(0, 0, 0, 0, 0, -1);
        main.port().add(new AtmosphereFog(main, regions));
        frames();
        assertChannel(SIZE / 2, SIZE / 2, BACK * Math.exp(-0.3 * 4), "through the ball's diameter");
        assertChannel(pixel(-0.5), SIZE / 2, BACK, "beside them: no fog");
        // The ray to the box's centre: a direction off the axis; the length through a box turned 45 degrees, along
        // that direction, from its slabs
        int px = pixel(3.0 / 6);
        double[] d = direction(px, SIZE / 2);
        double through = boxLength(d, new double[] {3, 0, -6}, Math.PI / 4, new double[] {1, 1.5, 1});
        assertThat(through).isGreaterThan(2);
        assertChannel(px, SIZE / 2, BACK * Math.exp(-0.3 * through), "through the turned box");
    }

    // ---------------------------------------------------------------- the scene

    /** The house's walls: the front 10 m ahead with a slit 2 m wide in the middle, the back 20 m ahead. */
    private void house() {
        rig.scene().set(0, quad, back, 0, 0, -20, 0, 0, 0, 1, 12, 12, 1);
        rig.scene().set(1, quad, front, -6, 0, -10, 0, 0, 0, 1, 5, 5, 1);
        rig.scene().set(3, quad, front, 6, 0, -10, 0, 0, 0, 1, 5, 5, 1);
    }

    /** The room's indoor zone, from the front wall to the back. */
    private long room() {
        long room = scene.create();
        scene.setTranslation(room, 0, 0, -15);
        scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(11, 5, 5)));
        update();
        return room;
    }

    private void update() {
        scene.update();
        regions.update();
    }

    /** Uniform outdoor fog of SIGMA per metre, of {@code albedo} lit by {@code ambient}, on through the scene's values. */
    private AtmosphereFogParameters fog(Vec3 albedo, Vec3 ambient) {
        AtmosphereFogParameters fog = AtmosphereFogParameters.DEFAULTS.withDensity(SIGMA).withHeightFalloff(0)
            .withAlbedo(albedo).withAmbient(ambient).withSunIlluminance(new Vec3(0, 0, 0));
        values().set(AtmosphereFog.HOOK, fog, true);
        return fog;
    }

    private ShadingHookValues values() {
        return ShadingHookValues.of(device);
    }

    private SceneView view(double x, double y, double z, double fx, double fy, double fz) {
        Camera camera = new Camera().position(x, y, z).set(fx, fy, fz, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        SceneView main = rig.toTarget(rig.main(camera));
        main.port().add(rig.forward(main).clearColor(0, 0, 0));
        return main;
    }

    private void frames() {
        rig.frames(3);
    }

    private static Material emissive(float e) {
        return new Material(MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1).withMetallic(0)
            .withEmissive(new Color(e, e, e, 1))).doubleSided(true);
    }

    // ---------------------------------------------------------------- reading pixels

    private void assertChannel(int x, int y, double expected, String what) {
        int[] rgba = rig.target().rgba(x, y);
        assertThat((double) rgba[0]).as("%s at %d, %d (expected %.1f)", what, x, y, 255 * expected)
            .isCloseTo(255 * expected, within(3.0));
    }

    /** The pixel column whose centre looks along x / -z = {@code slope} (90 degrees across 64 pixels). */
    private static int pixel(double slope) {
        return (int) Math.floor((slope + 1) / 2 * SIZE);
    }

    /** The unit direction through pixel ({@code x}, {@code y})'s centre of a camera looking along -z. */
    private static double[] direction(int x, int y) {
        double dx = (x + 0.5) / SIZE * 2 - 1;
        double dy = -((y + 0.5) / SIZE * 2 - 1);
        double l = Math.sqrt(dx * dx + dy * dy + 1);
        return new double[] {dx / l, dy / l, -1 / l};
    }

    /** The length of the ray from the origin along {@code d} through a box at {@code c}, turned about y, of half {@code h}. */
    private static double boxLength(double[] d, double[] c, double angle, double[] h) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        // The box's axes: x and z turned by angle about y
        double[][] axes = {{cos, 0, -sin}, {0, 1, 0}, {sin, 0, cos}};
        double near = -1e30;
        double far = 1e30;
        for (int i = 0; i < 3; i++) {
            double o = -(axes[i][0] * c[0] + axes[i][1] * c[1] + axes[i][2] * c[2]);
            double v = axes[i][0] * d[0] + axes[i][1] * d[1] + axes[i][2] * d[2];
            double a = (-h[i] - o) / v;
            double b = (h[i] - o) / v;
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
        }
        return Math.max(0, far - Math.max(near, 0));
    }
}
