package com.truesim.effects.atmosphere.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import com.truesim.effects.atmosphere.sky.SkyAssets;
import com.truesim.effects.atmosphere.sky.SkyComponents;
import com.truesim.effects.atmosphere.sky.SkySettings;
import com.truesim.effects.atmosphere.sky.SkyTime;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.AmbientOcclusion;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ScreenSpaceReflections;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.scene.ShadingHookComponent;
import com.truesim.engine.scene.ShadingHookSync;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.effects.atmosphere.fog.AtmosphereFog;
import com.truesim.effects.atmosphere.fog.FogRegions;
import com.truesim.effects.atmosphere.fog.IndoorZone;
import com.truesim.common.math.Vec3;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import javax.imageio.ImageIO;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * A landscape under the atmosphere, through the scene and its assets as an application draws it: a terrain 60 km
 * across (rolling ground about the camera, mountains rising beyond 3 km to 1.6 km), rocks, a white house and a pond,
 * as generated mesh and material assets on scene nodes ({@link SceneSync}); the sky from the sky components on an
 * environment node ({@link AtmosphereSync}: Graz, the bundled star catalogue); the sun and the moon as directional
 * lights with cascaded shadows; forward shading lit by the captured sky ({@link SkyCapture} into the
 * {@link Environment}), ambient occlusion and screen-space reflections (the pond); the atmosphere's sky and aerial
 * perspective; the engine's display transform (ACES). For the screenshots, the film and the benchmark.
 */
final class AtmosphereScene implements AutoCloseable {

    /** Graz: 47.07 N, 15.44 E, 350 m. */
    static final Observer GRAZ = new Observer(47.07, 15.44, 350);
    private static final int MATERIALS = 16;
    static final double TERRAIN_HALF = 30_000;
    private static final int TERRAIN_N = 640;

    final VulkanDevice device;
    final int width;
    final int height;
    final FrameSlots slots = new FrameSlots(2);
    final TaskScheduler tasks = new TaskScheduler();
    final AssetCache assets = new AssetCache(tasks);
    final PathAssets paths = new PathAssets(assets);
    final GeometryPool pool;
    final Materials materials;
    final Textures textures;
    final SceneResources resources;
    final GpuScene gpu;
    final MeshletRenderer renderer;
    final Lights lights;
    final FrameGraph graph;
    final SceneGraph scene = new SceneGraph(new ChangeClock());
    final SceneSync sync;
    final Atmosphere atmosphere;
    final AtmosphereSync sky;
    final Environment environment = Environment.uniform("atmosphere", 0, 0, 0);
    final SkyCapture capture;
    final Shadows shadows;
    final FogRegions regions;
    final AtmosphereFog fog;
    final ShadingHookSync hookSync;
    final Camera camera = new Camera();
    final SceneView view;
    final long environmentNode;
    private final GpuImage target;
    private final GpuImage shownTarget;
    private final GpuBuffer pixels;
    private final GpuBuffer shown;
    private int count;
    double gameSeconds;

    AtmosphereScene(VulkanDevice device, int width, int height, Quality quality, boolean landscape) {
        this.device = device;
        this.width = width;
        this.height = height;
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 60_000, 16), slots);
        materials = new Materials(device, MATERIALS, slots);
        textures = new Textures(device, slots);
        resources = new SceneResources(assets, pool, materials, textures);
        gpu = new GpuScene(device, pool, 1024, slots);
        renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT, slots);
        lights = new Lights(device, 4, 8, slots);
        graph = new FrameGraph(device, slots);
        AtmosphereQuality.apply(graph.settings(), quality);
        graph.settings().set(DisplayTransform.EXPOSURE, 1f);
        DisplayTransform.curve(graph.settings(), DisplayTransform.Curve.ACES);
        sync = new SceneSync(resources, scene, gpu);
        hookSync = new ShadingHookSync(scene, ShadingHookValues.of(device));
        regions = new FogRegions(scene);
        SkyAssets.install(paths);
        atmosphere = new Atmosphere(device, slots, graph.settings());
        environmentNode = scene.create();
        scene.components().set(environmentNode, SkyComponents.SKY, SkySettings.forQuality(quality, GRAZ));
        sky = new AtmosphereSync(scene, environmentNode, assets, paths, atmosphere);
        capture = new SkyCapture(atmosphere, environment, textures);
        lights.directional(0, 0, -1, 0, 0, 0, 0);
        lights.directional(1, 0, -1, 0, 0, 0, 0);
        lights.shadow(0, ShadowSpec.cascades(3, 2048, 400));
        lights.setCount(2);
        if (landscape) {
            build();
        }
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
        target = new GpuImage(device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        shownTarget = new GpuImage(device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT, usage,
            VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        shown = GpuBuffer.hostVisible(device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        camera.position(0, 2, 0).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), width, height, 0.1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 15, 1 << 22));
        shadows = new Shadows(geometry, lights);
        fog = new AtmosphereFog(view, regions).froxels(new VolumetricFog(atmosphere, view)).lighting(atmosphere);
        Key<Image> displayed = Key.image("test.displayed");
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0))
            .add(new ClusteredLights(lights, view)).add(shadows.view(view)).add(fog).add(new EnvironmentLighting(environment,
                view)).add(capture).add(new AtmosphereSky(atmosphere, view)).add(new AerialPerspective(atmosphere,
                view)).add(new AmbientOcclusion(view, null)).add(new ScreenSpaceReflections(view))
            .add(new Copy(pixels, width, height, Keys.SCENE_COLOR, "capture"))
            .add(new DisplayTransform().transform(Keys.SCENE_COLOR, displayed, DisplayEncoding.SDR_SRGB))
            .add(new Copy(shown, width, height, displayed, "capture displayed"))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", width, height, Format.R16G16B16A16_SFLOAT, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()))
            .output(displayed, new ExternalImage("shown", width, height, Format.R16G16B16A16_SFLOAT, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(shownTarget.handle(),
                shownTarget.view()));
    }

    /** The sky's clock: frozen at UT Julian day {@code jd}. */
    void at(double jd) {
        scene.components().set(environmentNode, SkyComponents.SKY_TIME, new SkyTime(jd, 0));
    }

    /**
     * The fog as content says it (engine-scene's ShadingHookComponent on the environment node): height fog of
     * {@code density} per metre at the ground, thinning by {@code falloff} per metre up; 0 turns it off.
     */
    void fog(float density, float falloff) {
        scene.components().set(environmentNode, ShadingHookComponent.TYPE, new ShadingHookComponent(AtmosphereFog.HOOK,
            MaterialData.builder(AtmosphereFog.HOOK).set("density", density).set("heightFalloff", falloff)
                .set("anisotropy", 0.7f).build().values(), density > 0));
    }

    /** A frame as an application runs it: loads, the scene, the sky and its lights, then the graph. */
    void step() {
        int slot = graph.beginFrame();
        slots.begin(slot);
        tasks.drainCompleted();
        scene.update();
        sync.update();
        hookSync.update();
        regions.update();
        sky.update(gameSeconds, camera.position(0), camera.position(1), camera.position(2));
        atmosphere.light(lights, 0, 1);
        view.set(1, MeshletRenderer.MODE_LIT);
        graph.submit(0, 0);
        slots.end();
    }

    /** {@code frames} frames, waited for. */
    void settle(int frames) {
        for (int f = 0; f < frames; f++) {
            step();
        }
        device.waitIdle();
    }

    /** Frames until every renderable is drawn and the star catalogue has loaded. */
    void load() throws InterruptedException {
        long deadline = System.nanoTime() + 300_000_000_000L;
        while (sync.instanceCount() < count || !sky.starsReady()) {
            step();
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("loading: " + sync.instanceCount() + " of " + count);
            }
            Thread.sleep(2);
        }
        settle(4);
    }

    /** The camera at ({@code x}, {@code y}, {@code z}) looking along azimuth {@code azimuth} (degrees from north
     * towards east) and {@code elevation} degrees up. */
    void look(double x, double y, double z, double azimuth, double elevation, double fov) {
        double a = Math.toRadians(azimuth);
        double e = Math.toRadians(elevation);
        double dx = Math.sin(a) * Math.cos(e);
        double dy = Math.sin(e);
        double dz = -Math.cos(a) * Math.cos(e);
        camera.position(x, y, z).set(dx, dy, dz, 0, 1, 0, Math.toRadians(fov), width, height, 0.1);
    }

    /** The last frame's scene color, linear RGB, scene units. */
    float[][] color() {
        return read(pixels);
    }

    /** The last frame as the display transform shows it (linear 0 to 1). */
    float[][] displayed() {
        return read(shown);
    }

    private float[][] read(GpuBuffer buffer) {
        float[][] rgb = new float[3][width * height];
        MemorySegment memory = buffer.memory();
        for (int i = 0; i < width * height; i++) {
            for (int c = 0; c < 3; c++) {
                rgb[c][i] = Float.float16ToFloat(memory.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 2L * c));
            }
        }
        return rgb;
    }

    /** The log-average luminance of the last frame, cd/m² (scene units over the atmosphere's). */
    double logAverageLuminance() {
        float[][] rgb = color();
        double sum = 0;
        int n = 0;
        for (int i = 0; i < width * height; i += 7) {
            double y = 0.2126 * rgb[0][i] + 0.7152 * rgb[1][i] + 0.0722 * rgb[2][i];
            if (Double.isFinite(y) && y / atmosphere.sceneUnits() > 1e-5) { // not space's black
                sum += Math.log(Math.max(y, 1e-12) / atmosphere.sceneUnits());
                n++;
            }
        }
        return Math.exp(sum / Math.max(n, 1));
    }

    /** The displayed image as an sRGB PNG. */
    void write(Path file) throws IOException {
        float[][] rgb = displayed();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < width * height; i++) {
            int packed = 0;
            for (int k = 0; k < 3; k++) {
                double v = Float.isFinite(rgb[k][i]) ? Math.max(0, Math.min(1, rgb[k][i])) : 1;
                v = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
                packed = packed << 8 | (int) Math.round(v * 255);
            }
            image.setRGB(i % width, i / width, packed);
        }
        ImageIO.write(image, "png", file.toFile());
    }

    // The landscape -------------------------------------------------------------------------------------------------

    private static AssetRef<MeshData> mesh(int seed, java.util.function.Supplier<MeshData> make) {
        return AssetRef.generated(RenderAssets.MESH, (Generator<Integer, MeshData>) (unused, s) -> make.get(), seed, 1);
    }

    private static AssetRef<MaterialData> material(int seed, float r, float g, float b, float roughness,
        float metallic) {
        return AssetRef.generated(RenderAssets.MATERIAL, (Generator<Integer, MaterialData>) (unused, s) ->
            MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", r, g, b, 1f)
                .set("roughness", roughness).set("metallic", metallic).build(), seed, 1);
    }

    private static final AssetRef<MeshData> TERRAIN = mesh(100, AtmosphereScene::terrain);
    private static final AssetRef<MeshData> ROCK = mesh(101, () -> TestMeshes.rock(5));
    private static final AssetRef<MeshData> BOX = mesh(102, TestMeshes::box);
    private static final AssetRef<MeshData> QUAD = mesh(103, TestMeshes::quad);
    private static final AssetRef<MaterialData> GROUND = material(100, 0.07f, 0.11f, 0.035f, 0.95f, 0);
    private static final AssetRef<MaterialData> STONE = material(101, 0.38f, 0.36f, 0.33f, 0.85f, 0);
    private static final AssetRef<MaterialData> WHITE = material(102, 0.8f, 0.78f, 0.74f, 0.6f, 0);
    private static final AssetRef<MaterialData> ROOF = material(103, 0.45f, 0.12f, 0.08f, 0.7f, 0);
    private static final AssetRef<MaterialData> WOOD = material(105, 0.22f, 0.16f, 0.11f, 0.8f, 0);
    private static final AssetRef<MaterialData> WATER = material(104, 0.02f, 0.03f, 0.03f, 0.04f, 0);

    private long node(ComponentStore<Renderable> renderables, AssetRef<MeshData> mesh, AssetRef<MaterialData> material,
        double x, double y, double z, double qx, double qy, double qz, double qw, double sx, double sy, double sz) {
        long node = scene.create();
        scene.setLocal(node, x, y, z, qx, qy, qz, qw, sx, sy, sz);
        renderables.set(node, Renderable.of(mesh, List.of(material)));
        count++;
        return node;
    }

    /** The room's centre on the ground (x, floor height, z): east of the camera's start, its window facing east. */
    static final double ROOM_X = 40;
    static final double ROOM_Z = 25;

    /** The room's floor height. */
    static double roomFloor() {
        double top = -1e9;
        for (double x = -4; x <= 4; x += 0.5) {
            for (double z = -3.5; z <= 3.5; z += 0.5) {
                top = Math.max(top, height(ROOM_X + x, ROOM_Z + z));
            }
        }
        return top + 0.1;
    }

    /**
     * A room 6 x 3 x 5 m on the ground at ({@link #ROOM_X}, {@link #ROOM_Z}) with a window 1.6 x 1.2 m in its east wall
     * (+x), dark wooden walls, and an indoor zone of dusty air of its own (atmosphere-fog's IndoorZone, replacing the
     * outdoor fog, a tenth of the sky's light inside; the froxels' shadows decide where the sun reaches): light shafts
     * through the window.
     */
    void room() {
        ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
        double y = roomFloor();
        wall(renderables, -3.2, 3.2, -0.2, 0, -2.7, 2.7, y);       // floor
        wall(renderables, -3.2, 3.2, 3, 3.2, -2.7, 2.7, y);        // roof
        wall(renderables, -3.2, -3, 0, 3, -2.7, 2.7, y);           // west wall
        wall(renderables, -3, 3, 0, 3, -2.7, -2.5, y);             // north wall
        wall(renderables, -3, 3, 0, 3, 2.5, 2.7, y);               // south wall
        wall(renderables, 3, 3.2, 0, 1, -2.5, 2.5, y);             // east wall, below the window
        wall(renderables, 3, 3.2, 2.2, 3, -2.5, 2.5, y);           // above it
        wall(renderables, 3, 3.2, 1, 2.2, -2.5, -0.8, y);          // beside it
        wall(renderables, 3, 3.2, 1, 2.2, 0.8, 2.5, y);
        long zone = scene.create();
        scene.setTranslation(zone, ROOM_X, y + 1.5, ROOM_Z);
        scene.components().set(zone, IndoorZone.TYPE, IndoorZone.box(new Vec3(3, 1.5, 2.5))
            .replacing(0.12f, new Vec3(0.9, 0.9, 0.9)).withLight(0.1f));
    }

    /** A wall from (x0, y0, z0) to (x1, y1, z1), relative to the room's centre on its floor. */
    private void wall(ComponentStore<Renderable> renderables, double x0, double x1, double y0, double y1, double z0,
        double z1, double floor) {
        node(renderables, BOX, WOOD, ROOM_X + (x0 + x1) / 2, floor + (y0 + y1) / 2, ROOM_Z + (z0 + z1) / 2, 0, 0, 0, 1,
            (x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2);
    }

    private void build() {
        ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
        node(renderables, TERRAIN, GROUND, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < 70; i++) {
            double x = random.nextDouble(-120, 120);
            double z = random.nextDouble(-160, -6);
            double s = random.nextDouble(0.4, 2.4);
            double[] q = unit(random.nextDouble(-1, 1), random.nextDouble(-1, 1), random.nextDouble(-1, 1),
                random.nextDouble(-1, 1));
            node(renderables, ROCK, STONE, x, height(x, z) + 0.3 * s, z, q[0], q[1], q[2], q[3], s, s, s);
        }
        // A house on the left: white walls, a dark red roof
        double hx = -14;
        double hz = -30;
        double hy = height(hx, hz);
        node(renderables, BOX, WHITE, hx, hy + 2.4, hz, 0, Math.sin(0.2), 0, Math.cos(0.2), 4, 2.6, 3);
        node(renderables, BOX, ROOF, hx, hy + 5.3, hz, 0, Math.sin(0.2), 0, Math.cos(0.2), 4.3, 0.35, 3.3);
        // The pond: a still, dark mirror of the sky in front
        double px = 6;
        double pz = -18;
        node(renderables, QUAD, WATER, px, height(px, pz) + 0.25, pz, -Math.sqrt(0.5), 0, 0, Math.sqrt(0.5), 9, 7, 1);
        room();
    }

    private static double[] unit(double x, double y, double z, double w) {
        double l = Math.sqrt(x * x + y * y + z * z + w * w);
        return new double[] {x / l, y / l, z / l, w / l};
    }

    /** The terrain's height at ({@code x}, {@code z}), metres. */
    static double height(double x, double z) {
        double r = Math.hypot(x, z);
        double hills = 2.5 * fbm(x / 260, z / 260, 4) + 0.6 * fbm(x / 40, z / 40, 3);
        double rise = smooth((r - 2500) / 12_000);
        double mountains = 1700 * rise * Math.pow(Math.max(0, ridged(x / 7000, z / 7000, 5)), 1.6);
        return hills + mountains;
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Fractal value noise in about -1 to 1. */
    private static double fbm(double x, double z, int octaves) {
        double sum = 0;
        double amplitude = 0.5;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * (2 * noise(x, z, o) - 1);
            x *= 2.03;
            z *= 2.03;
            amplitude *= 0.5;
        }
        return sum;
    }

    /** Ridged fractal noise in about 0 to 1. */
    private static double ridged(double x, double z, int octaves) {
        double sum = 0;
        double amplitude = 0.6;
        for (int o = 0; o < octaves; o++) {
            double n = 1 - Math.abs(2 * noise(x, z, o + 7) - 1);
            sum += amplitude * n * n;
            x *= 2.1;
            z *= 2.1;
            amplitude *= 0.45;
        }
        return sum;
    }

    /** Smooth value noise in 0 to 1. */
    private static double noise(double x, double z, int seed) {
        double fx = Math.floor(x);
        double fz = Math.floor(z);
        double tx = x - fx;
        double tz = z - fz;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        long ix = (long) fx;
        long iz = (long) fz;
        double a = hash(ix, iz, seed);
        double b = hash(ix + 1, iz, seed);
        double c = hash(ix, iz + 1, seed);
        double d = hash(ix + 1, iz + 1, seed);
        return a + (b - a) * tx + (c - a) * tz + (a - b - c + d) * tx * tz;
    }

    private static double hash(long x, long z, int seed) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ seed * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** The terrain: {@code TERRAIN_N}² quads over 60 km, finest about the origin (spacing growing with distance). */
    static MeshData terrain() {
        int n = TERRAIN_N;
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        for (int j = 0; j <= n; j++) {
            double v = 2.0 * j / n - 1;
            double z = TERRAIN_HALF * v * Math.abs(v);
            for (int i = 0; i <= n; i++) {
                double u = 2.0 * i / n - 1;
                double x = TERRAIN_HALF * u * Math.abs(u);
                int k = j * (n + 1) + i;
                positions[3 * k] = (float) x;
                positions[3 * k + 1] = (float) height(x, z);
                positions[3 * k + 2] = (float) z;
            }
        }
        int[] indices = new int[n * n * 6];
        int at = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * (n + 1) + i;
                indices[at++] = a;
                indices[at++] = a + n + 1;
                indices[at++] = a + 1;
                indices[at++] = a + 1;
                indices[at++] = a + n + 1;
                indices[at++] = a + n + 2;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
            .attribute(VertexAttribute.NORMAL, TestMeshes.normals(positions, indices)).submesh(indices)
            .buildHierarchy();
    }

    @Override
    public void close() {
        device.waitIdle();
        sky.close();
        sync.close();
        graph.close();
        fog.close();
        shadows.close();
        capture.close();
        atmosphere.close();
        target.close();
        shownTarget.close();
        pixels.close();
        shown.close();
        renderer.close();
        gpu.close();
        lights.close();
        resources.close();
        textures.close();
        materials.close();
        pool.close();
        assets.close();
        tasks.close();
    }

    /** Copies an image of the view into {@code pixels} after everything drawn over it. */
    private record Copy(GpuBuffer pixels, int width, int height, Key<Image> key, String name) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(key);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.modify(key);
            g.pass(name).read(image, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferRowLength(width).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                    region.imageExtent().set(width, height, 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(image),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
                }
            });
            g.provide(key, image);
        }
    }
}
