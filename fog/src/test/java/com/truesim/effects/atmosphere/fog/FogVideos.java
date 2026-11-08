package com.truesim.effects.atmosphere.fog;

import com.truesim.common.math.Vec3;
import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.fog.hooks.AtmosphereFogParameters;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.EnvironmentMaps;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.SortedTransparency;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.TransmissionParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.render.testing.TestRenderer;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.core.SceneGraph;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Films of the fog as frame sequences, 30 frames a second (TRUESIM_SCREENSHOTS=true; {@code -Pscreenshots=<directory>},
 * else build/screenshots; a directory of PNGs per film, f0000.png on, for ffmpeg):
 * <ul>
 *   <li>{@code fog-walk-indoors}: a walk through height fog up to a house's window, along to its door and into the
 *   room (an indoor zone taking the fog out), then turning back to look out: the air outside fogged up to the glass
 *   and the doorway, the room clear;</li>
 *   <li>{@code fog-walk-zone-left} and {@code -right}: the same walk without the zone (the room as full of fog as the
 *   meadow) and with it, for a side by side;</li>
 *   <li>{@code fog-volumes-orbit}: an ellipsoid fog bank in a valley and a turned box of fog at its mouth, the camera
 *   circling them while their densities rise and fall.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class FogVideos {

    private static final int FPS = 30;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final double EYE = 1.6;

    @Test
    void walkIntoTheRoomAndLookBack() throws IOException {
        Path out = directory("fog-walk-indoors");
        try (TestGpu gpu = TestGpu.open()) {
            House house = new House(gpu, 1280, 720);
            Camera camera = new Camera();
            house.view(camera);
            int frames = (int) (WALK_SECONDS * FPS);
            for (int f = 0; f < frames; f++) {
                walk(camera, f / (double) FPS, 1280, 720);
                house.rig.frames(1);
                write(house.rig.target().shot(), out.resolve(frame(f)));
            }
            house.close();
        }
    }

    @Test
    void theSameWalkWithoutAndWithTheZone() throws IOException {
        Path left = directory("fog-walk-zone-left");
        Path right = directory("fog-walk-zone-right");
        try (TestGpu gpu = TestGpu.open()) {
            House house = new House(gpu, 640, 720);
            Camera camera = new Camera();
            house.view(camera);
            int frames = (int) (WALK_SECONDS * FPS);
            for (int f = 0; f < frames; f++) {
                walk(camera, f / (double) FPS, 640, 720);
                for (boolean zone : new boolean[] {false, true}) {
                    house.zone(zone);
                    house.rig.frames(1);
                    write(house.rig.target().shot(), (zone ? right : left).resolve(frame(f)));
                }
            }
            house.close();
        }
    }

    @Test
    void fogVolumesInAValleyAsTheCameraCirclesThem() throws IOException {
        Path out = directory("fog-volumes-orbit");
        int width = 1280;
        int height = 720;
        try (TestGpu gpu = TestGpu.open()) {
            TestRenderer rig = gpu.closing(TestRenderer.of(gpu.device(), width, height));
            Textures textures = gpu.closing(new Textures(gpu.device()));
            Materials materials = gpu.closing(new Materials(gpu.device(), 16));
            rig.scene().setMaterials(materials);
            int box = rig.add(TestMeshes.box());
            int grass = materials.add(matte(0.30f, 0.36f, 0.22f));
            int hill = materials.add(matte(0.36f, 0.34f, 0.26f));
            int post = materials.add(matte(0.55f, 0.15f, 0.12f));
            int barn = materials.add(matte(0.70f, 0.62f, 0.50f));
            int[] id = {0};
            Placer place = (material, x, y, z, angleZ, hx, hy, hz) -> rig.scene().set(id[0]++, box, material, x, y, z,
                0, 0, (float) Math.sin(angleZ / 2), (float) Math.cos(angleZ / 2), (float) hx, (float) hy, (float) hz);
            place.at(grass, 0, -0.05, 0, 0, 120, 0.05, 120);
            // A valley along z: its floor about 8 m wide, hillsides rising 25 degrees either side
            double slope = Math.toRadians(25);
            place.at(hill, -4 - 9 * Math.cos(slope), 9 * Math.sin(slope) - 0.3, 0, -slope, 9, 0.3, 80);
            place.at(hill, 4 + 9 * Math.cos(slope), 9 * Math.sin(slope) - 0.3, 0, slope, 9, 0.3, 80);
            // Posts along the valley floor, a barn at its far end
            for (int i = 0; i < 11; i++) {
                place.at(post, i % 2 == 0 ? -2.5 : 2.5, 1, -30 + 6 * i, 0, 0.15, 1, 0.15);
            }
            place.at(barn, 0, 2, -34, 0, 3, 2, 2.5);

            SceneGraph scene = new SceneGraph(new ChangeClock());
            long bank = scene.create();
            scene.setTranslation(bank, 0, 1.2, -8);
            long smoke = scene.create();
            scene.setTranslation(smoke, 0, 2.2, 15);
            double turn = Math.toRadians(30);
            scene.setRotation(smoke, 0, Math.sin(turn / 2), 0, Math.cos(turn / 2));
            FogRegions regions = new FogRegions(scene);
            ShadingHookValues.of(gpu.device()).set(AtmosphereFog.HOOK, AtmosphereFogParameters.DEFAULTS
                .withDensity(0.008f).withHeightFalloff(0.12f).withAlbedo(new Vec3(0.92, 0.93, 0.95))
                .withAmbient(new Vec3(0.55, 0.6, 0.68)).withSunDirection(new Vec3(-0.4, 0.45, -0.8))
                .withSunIlluminance(new Vec3(1.1, 1.0, 0.85)).withAnisotropy(0.5f).withSkyDistance(3000), true);
            int sky = textures.add(TextureFormat.RGBA16F, 64, 32, Textures.GENERATE, sky(64, 32));
            Environment environment = Environment.panorama("pale sky", sky, 64);
            EnvironmentMaps.Quality quality = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);
            Camera camera = new Camera();
            SceneView main = rig.toTarget(rig.main(camera.position(0, 9, 32).set(0, -0.2, -1, 0, 1, 0,
                Math.toRadians(60), width, height, 0.05)));
            SceneGeometry geometry = SceneGeometry.meshlets(rig.renderer());
            main.port().add(rig.forward(main)).add(new EnvironmentLighting(environment, main, quality))
                .add(new SkyBackground(environment, main, quality)).add(new SortedTransparency(geometry, main))
                .add(main.port().own(new AtmosphereFog(main, regions)));
            int frames = 14 * FPS;
            for (int f = 0; f < frames; f++) {
                double t = f / (double) FPS;
                // Densities: the bank breathing from thin to thick and back twice, the box out of phase with it
                float thick = (float) (0.02 + 0.4 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t / 7)));
                float box2 = (float) (0.02 + 0.4 * (0.5 + 0.5 * Math.cos(2 * Math.PI * t / 7)));
                scene.components().set(bank, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(6, 3.5, 18), thick)
                    .withFalloff(0.25f).withBlendDistance(2.5f));
                scene.components().set(smoke, FogVolume.TYPE, FogVolume.box(new Vec3(3.5, 2.2, 3), box2)
                    .withAlbedo(new Vec3(0.7, 0.7, 0.72)).withBlendDistance(0.8f));
                scene.update();
                regions.update();
                // Three quarters of a circle round the valley, rising a little
                double a = Math.toRadians(-20) + 1.5 * Math.PI * smooth(t / 14.0);
                double ex = 30 * Math.sin(a);
                double ez = 30 * Math.cos(a) + 2;
                double ey = 10 + 2 * Math.sin(Math.PI * t / 14);
                camera.position(ex, ey, ez).set(-ex, 1.5 - ey, 2 - ez, 0, 1, 0, Math.toRadians(60), width,
                    height, 0.05);
                rig.frames(1);
                write(rig.target().shot(), out.resolve(frame(f)));
            }
            rig.scene().setMaterials(null);
        }
    }

    // ---------------------------------------------------------------- the walk

    private static final double WALK_SECONDS = 14;

    /**
     * The camera along the walk at {@code t} seconds: up the meadow to the window (0 to 5 s), along the front to the
     * door (5 to 7), through it into the room (7 to 10), turning to look back out at the window and the doorway (10 to
     * 12.5), then still.
     */
    private static void walk(Camera camera, double t, int width, int height) {
        double[][] keys = {
            // t, x, z, yaw (0: along -z; positive turns left), pitch
            {0, -2, 16, 0, -0.06},
            {5, -2, -3.4, 0, -0.04},
            {5.6, -2, -3.4, -0.25, -0.03},
            {7, 1.6, -3.4, 0, -0.03},
            {10, 1.3, -10.5, 0, -0.05},
            {12.5, 1.3, -10.5, Math.PI - 0.2, -0.04},
            {WALK_SECONDS, 1.3, -10.5, Math.PI - 0.2, -0.04}};
        int k = 0;
        while (k < keys.length - 2 && t > keys[k + 1][0]) {
            k++;
        }
        double[] a = keys[k];
        double[] b = keys[k + 1];
        double s = smooth(Math.clamp((t - a[0]) / (b[0] - a[0]), 0, 1));
        double x = a[1] + (b[1] - a[1]) * s;
        double z = a[2] + (b[2] - a[2]) * s;
        double yaw = a[3] + (b[3] - a[3]) * s;
        double pitch = a[4] + (b[4] - a[4]) * s;
        // A gentle bob while walking
        double moving = Math.hypot(b[1] - a[1], b[2] - a[2]) > 0 ? Math.sin(Math.PI * s) : 0;
        double y = EYE + 0.02 * Math.sin(2 * Math.PI * 1.8 * t) * moving;
        double fx = -Math.sin(yaw) * Math.cos(pitch);
        double fz = -Math.cos(yaw) * Math.cos(pitch);
        camera.position(x, y, z).set(fx, Math.sin(pitch), fz, 0, 1, 0, Math.toRadians(60), width, height, 0.05);
    }

    private static double smooth(double s) {
        return s * s * (3 - 2 * s);
    }

    /**
     * FogScreenshots' house in height fog, with a door: the room inside x -4 to 4, y 0 to 3, z -14 to -6, an indoor
     * zone; its front wall at z -6 to -5.8 with a glazed window (x -3 to -1, y 0.8 to 2.2) and an open door (x 1 to
     * 2.2, y 0 to 2.4); posts on the meadow; a fog volume drifting to the left.
     */
    private static final class House {

        final TestRenderer rig;
        final SceneGraph scene;
        final FogRegions regions;
        final long room;
        final Environment environment;
        final EnvironmentMaps.Quality quality;
        final int width;
        final int height;

        House(TestGpu gpu, int width, int height) {
            this.width = width;
            this.height = height;
            rig = gpu.closing(TestRenderer.of(gpu.device(), width, height));
            Textures textures = gpu.closing(new Textures(gpu.device()));
            Materials materials = gpu.closing(new Materials(gpu.device(), 16));
            rig.scene().setMaterials(materials);
            int box = rig.add(TestMeshes.box());
            int ground = materials.add(matte(0.32f, 0.36f, 0.26f));
            int floor = materials.add(matte(0.45f, 0.36f, 0.26f));
            int wall = materials.add(matte(0.75f, 0.68f, 0.58f));
            int roof = materials.add(matte(0.35f, 0.18f, 0.14f));
            int post = materials.add(matte(0.55f, 0.15f, 0.12f));
            int table = materials.add(matte(0.75f, 0.40f, 0.12f));
            int painted = materials.add(matte(0.10f, 0.22f, 0.65f));
            int lamp = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1))
                .withEmissive(new Color(2.5f, 2.0f, 1.2f, 1))));
            int glass = materials.add(new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(0.02f)
                .withMetallic(0)).feature(TransmissionParameters.DEFAULTS.withTransmissionFactor(1))
                .doubleSided(true));
            int[] id = {0};
            Placer place = (material, x, y, z, angleZ, hx, hy, hz) -> rig.scene().set(id[0]++, box, material, x, y,
                z, 0, 0, 0, 1, (float) hx, (float) hy, (float) hz);
            place.at(ground, 0, -0.05, 0, 0, 80, 0.05, 80);
            place.at(floor, 0, 0.005, -10, 0, 4, 0.005, 4);
            // The front wall, z -6 to -5.8: beside the window, under and over it, between window and door, over the
            // door, beside the door
            place.at(wall, -3.6, 1.5, -5.9, 0, 0.6, 1.5, 0.1);
            place.at(wall, -2, 0.4, -5.9, 0, 1, 0.4, 0.1);
            place.at(wall, -2, 2.6, -5.9, 0, 1, 0.4, 0.1);
            place.at(wall, 0, 1.5, -5.9, 0, 1, 1.5, 0.1);
            place.at(wall, 1.6, 2.7, -5.9, 0, 0.6, 0.3, 0.1);
            place.at(wall, 3.2, 1.5, -5.9, 0, 1, 1.5, 0.1);
            place.at(glass, -2, 1.5, -5.9, 0, 1, 0.7, 0.01);
            place.at(painted, 0, 1.5, -14.1, 0, 4.2, 1.5, 0.1);
            place.at(wall, -4.1, 1.5, -10, 0, 0.1, 1.5, 4.2);
            place.at(wall, 4.1, 1.5, -10, 0, 0.1, 1.5, 4.2);
            place.at(roof, 0, 3.1, -10, 0, 4.4, 0.1, 4.4);
            place.at(table, -1.5, 0.4, -11, 0, 1.2, 0.4, 0.7);
            place.at(lamp, -1.5, 0.95, -11, 0, 0.15, 0.15, 0.15);
            for (int i = 0; i < 6; i++) {
                place.at(post, -7 + 0.3 * i, 1, 6 - 5 * i, 0, 0.15, 1, 0.15);
                place.at(post, 7 - 0.3 * i, 1, 6 - 5 * i, 0, 0.15, 1, 0.15);
                place.at(post, i % 2 == 0 ? -4.5 : 4.2, 1, -2 + 5 * i, 0, 0.15, 1, 0.15);
            }
            scene = new SceneGraph(new ChangeClock());
            room = scene.create();
            scene.setTranslation(room, 0, 1.5, -10);
            long bank = scene.create();
            scene.setTranslation(bank, -9, 0.5, -2);
            scene.components().set(bank, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(4, 1.6, 3), 0.6f)
                .withFalloff(0.6f).withBlendDistance(1.2f));
            regions = new FogRegions(scene);
            zone(true);
            ShadingHookValues.of(gpu.device()).set(AtmosphereFog.HOOK, AtmosphereFogParameters.DEFAULTS
                .withDensity(0.045f).withHeightFalloff(0.12f).withAlbedo(new Vec3(0.92, 0.93, 0.95))
                .withAmbient(new Vec3(0.55, 0.6, 0.68)).withSunDirection(new Vec3(-0.4, 0.45, -0.8))
                .withSunIlluminance(new Vec3(1.1, 1.0, 0.85)).withAnisotropy(0.5f).withSkyDistance(3000), true);
            int sky = textures.add(TextureFormat.RGBA16F, 64, 32, Textures.GENERATE, sky(64, 32));
            environment = Environment.panorama("pale sky", sky, 64);
            quality = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);
        }

        /** The room an indoor zone, or not. */
        void zone(boolean on) {
            if (on) {
                scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(4, 1.5, 4)).withLight(0.15f));
            } else {
                scene.components().remove(room, IndoorZone.TYPE);
            }
            scene.update();
            regions.update();
        }

        /** The main view through {@code camera}, fogged. */
        void view(Camera camera) {
            camera.position(0, EYE, 10).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), width, height, 0.05);
            SceneView main = rig.toTarget(rig.main(camera));
            SceneGeometry geometry = SceneGeometry.meshlets(rig.renderer());
            main.port().add(rig.forward(main)).add(new EnvironmentLighting(environment, main, quality))
                .add(new SkyBackground(environment, main, quality)).add(new SortedTransparency(geometry, main))
                .add(main.port().own(new AtmosphereFog(main, regions)));
        }

        void close() {
            rig.scene().setMaterials(null);
        }
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Placer {
        void at(int material, double x, double y, double z, double angleZ, double hx, double hy, double hz);
    }

    private static Material matte(float r, float g, float b) {
        return new Material(MR.withBaseColor(new Color(r, g, b, 1)).withRoughness(0.9f).withMetallic(0));
    }

    private static Path directory(String name) throws IOException {
        Path out = Path.of(System.getProperty("truesim.screenshots", "build/screenshots")).resolve(name);
        Files.createDirectories(out);
        return out;
    }

    private static String frame(int f) {
        return String.format(Locale.ROOT, "f%04d.png", f);
    }

    /** A pale sky: bright towards the horizon, bluer overhead, the ground below a dim green-grey. */
    private static MemorySegment sky(int width, int height) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            double up = Math.cos(Math.PI * (y + 0.5) / height);
            double[] c = up >= 0
                ? new double[] {0.62 - 0.25 * up, 0.68 - 0.2 * up, 0.78 - 0.05 * up}
                : new double[] {0.18, 0.2, 0.16};
            for (int x = 0; x < width; x++) {
                long at = 8L * (y * width + x);
                for (int k = 0; k < 3; k++) {
                    texels.set(half, at + 2L * k, Float.floatToFloat16((float) c[k]));
                }
                texels.set(half, at + 6, Float.floatToFloat16(1));
            }
        }
        return texels;
    }

    private static final int[] SRGB = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            SRGB[i] = (int) Math.round(Math.clamp(s, 0, 1) * 255);
        }
    }

    /** The linear frame as sRGB, a PNG. */
    private static void write(BufferedImage linear, Path file) throws IOException {
        int w = linear.getWidth();
        int h = linear.getHeight();
        int[] row = linear.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < row.length; i++) {
            int argb = row[i];
            row[i] = SRGB[(argb >> 16) & 255] << 16 | SRGB[(argb >> 8) & 255] << 8 | SRGB[argb & 255];
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, w, h, row, 0, w);
        ImageIO.write(image, "png", file.toFile());
    }
}
