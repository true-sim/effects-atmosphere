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
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Pictures of the fog (TRUESIM_SCREENSHOTS=true; {@code -Pscreenshots=<directory>}, else build/screenshots): a house
 * with a glazed window in height fog under a pale sky, a fog volume drifting on the meadow, the room an indoor zone.
 * From outside, the air up to the glass is fogged and the room behind it clear; from inside, the room is clear and the
 * fog begins at the window. Each also without the zone, for comparison: the room then full of fog.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class FogScreenshots {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;

    @Test
    void fogOutsideAWindowFromOutsideAndInside() throws IOException {
        Path out = Path.of(System.getProperty("truesim.screenshots", "build/screenshots"));
        Files.createDirectories(out);
        try (TestGpu gpu = TestGpu.open()) {
            TestRenderer rig = gpu.closing(TestRenderer.of(gpu.device(), WIDTH, HEIGHT));
            Textures textures = gpu.closing(new Textures(gpu.device()));
            Materials materials = gpu.closing(new Materials(gpu.device(), 16));
            rig.scene().setMaterials(materials);
            int box = rig.add(TestMeshes.box());
            int ground = materials.add(matte(0.32f, 0.36f, 0.26f));
            int wall = materials.add(matte(0.75f, 0.68f, 0.58f));
            int roof = materials.add(matte(0.35f, 0.18f, 0.14f));
            int post = materials.add(matte(0.55f, 0.15f, 0.12f));
            int table = materials.add(matte(0.75f, 0.40f, 0.12f));
            int painted = materials.add(matte(0.10f, 0.22f, 0.65f));
            int lamp = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1))
                .withEmissive(new Color(2.5f, 2.0f, 1.2f, 1))));
            int glass = materials.add(new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(0.02f)
                .withMetallic(0)).feature(TransmissionParameters.DEFAULTS.withTransmissionFactor(1)).doubleSided(true));
            int[] id = {0};
            Placer place = (material, x, y, z, hx, hy, hz) -> rig.scene().set(id[0]++, box, material, x, y, z, 0, 0, 0,
                1, (float) hx, (float) hy, (float) hz);
            place.at(ground, 0, -0.05, 0, 80, 0.05, 80);
            // The house: the room inside x -4 to 4, y 0 to 3, z -14 to -6; the window x -1.5 to 1.5, y 0.8 to 2.2
            place.at(wall, -2.85, 1.5, -5.9, 1.35, 1.5, 0.1);
            place.at(wall, 2.85, 1.5, -5.9, 1.35, 1.5, 0.1);
            place.at(wall, 0, 0.4, -5.9, 1.5, 0.4, 0.1);
            place.at(wall, 0, 2.6, -5.9, 1.5, 0.4, 0.1);
            place.at(painted, 0, 1.5, -14.1, 4.2, 1.5, 0.1);
            place.at(wall, -4.1, 1.5, -10, 0.1, 1.5, 4.2);
            place.at(wall, 4.1, 1.5, -10, 0.1, 1.5, 4.2);
            place.at(roof, 0, 3.1, -10, 4.4, 0.1, 4.4);
            place.at(glass, 0, 1.5, -5.9, 1.5, 0.7, 0.01);
            place.at(table, 0, 0.4, -11, 1.2, 0.4, 0.7);
            place.at(lamp, 0, 0.95, -11, 0.15, 0.15, 0.15);
            // Posts on the meadow, near and far
            for (int i = 0; i < 6; i++) {
                place.at(post, -7 + 0.3 * i, 1, 6 - 5 * i, 0.15, 1, 0.15);
                place.at(post, 7 - 0.3 * i, 1, 6 - 5 * i, 0.15, 1, 0.15);
                place.at(post, i % 2 == 0 ? -1 : 1.2, 1, -2 + 5 * i, 0.15, 1, 0.15);
            }
            // The room as an indoor zone; a fog volume drifting on the meadow to the left
            SceneGraph scene = new SceneGraph(new ChangeClock());
            long room = scene.create();
            scene.setTranslation(room, 0, 1.5, -10);
            scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(4, 1.5, 4)).withLight(0.15f));
            long bank = scene.create();
            scene.setTranslation(bank, -9, 0.5, -2);
            scene.components().set(bank, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(4, 1.6, 3), 0.6f)
                .withFalloff(0.6f).withBlendDistance(1.2f));
            scene.update();
            FogRegions regions = new FogRegions(scene);
            regions.update();
            ShadingHookValues.of(gpu.device()).set(AtmosphereFog.HOOK, AtmosphereFogParameters.DEFAULTS
                .withDensity(0.045f).withHeightFalloff(0.12f).withAlbedo(new Vec3(0.92, 0.93, 0.95))
                .withAmbient(new Vec3(0.55, 0.6, 0.68)).withSunDirection(new Vec3(-0.4, 0.45, -0.8))
                .withSunIlluminance(new Vec3(1.1, 1.0, 0.85)).withAnisotropy(0.5f).withSkyDistance(3000), true);
            int sky = textures.add(TextureFormat.RGBA16F, 64, 32, Textures.GENERATE, sky(64, 32));
            Environment environment = Environment.panorama("pale sky", sky, 64);
            EnvironmentMaps.Quality quality = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);

            record Shot(String name, double[] eye, double[] at) {
            }
            Shot[] shots = {
                new Shot("fog-window-from-outside", new double[] {-0.2, 1.6, 4.5}, new double[] {0, 1.4, -10}),
                new Shot("fog-window-from-inside", new double[] {-1.5, 1.6, -12.8}, new double[] {1.0, 1.2, 4})};
            SceneView previous = null;
            for (Shot shot : shots) {
                for (boolean zone : new boolean[] {true, false}) {
                    if (zone) {
                        scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(4, 1.5, 4))
                            .withLight(0.15f));
                    } else {
                        scene.components().remove(room, IndoorZone.TYPE);
                    }
                    scene.update();
                    regions.update();
                    if (previous != null) {
                        rig.graph().removeView(previous.port());
                    }
                    double fx = shot.at[0] - shot.eye[0];
                    double fy = shot.at[1] - shot.eye[1];
                    double fz = shot.at[2] - shot.eye[2];
                    Camera camera = new Camera().position(shot.eye[0], shot.eye[1], shot.eye[2])
                        .set(fx, fy, fz, 0, 1, 0, Math.toRadians(60), WIDTH, HEIGHT, 0.05);
                    SceneView main = rig.toTarget(rig.main(camera));
                    SceneGeometry geometry = SceneGeometry.meshlets(rig.renderer());
                    main.port().add(rig.forward(main)).add(new EnvironmentLighting(environment, main, quality))
                        .add(new SkyBackground(environment, main, quality)).add(new SortedTransparency(geometry, main))
                        .add(main.port().own(new AtmosphereFog(main, regions)));
                    previous = main;
                    rig.frames(4);
                    write(rig.target().shot(), out.resolve(shot.name + (zone ? "" : "-without-zone") + ".png"));
                }
            }
            rig.scene().setMaterials(null);
        }
    }

    @FunctionalInterface
    private interface Placer {
        void at(int material, double x, double y, double z, double hx, double hy, double hz);
    }

    private static Material matte(float r, float g, float b) {
        return new Material(MR.withBaseColor(new Color(r, g, b, 1)).withRoughness(0.9f).withMetallic(0));
    }

    /** A pale sky: bright towards the horizon, bluer overhead, the ground below a dim green-grey. */
    private static MemorySegment sky(int width, int height) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            double up = Math.cos(Math.PI * (y + 0.5) / height); // 1 at the top row
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

    /** The linear frame as sRGB, a PNG. */
    private static void write(BufferedImage linear, Path file) throws IOException {
        BufferedImage image = new BufferedImage(linear.getWidth(), linear.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < linear.getHeight(); y++) {
            for (int x = 0; x < linear.getWidth(); x++) {
                int argb = linear.getRGB(x, y);
                int r = srgb((argb >> 16) & 255);
                int g = srgb((argb >> 8) & 255);
                int b = srgb(argb & 255);
                image.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static int srgb(int linear) {
        double v = linear / 255.0;
        double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.clamp(s, 0, 1) * 255);
    }
}
