package com.truesim.effects.atmosphere.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

import com.truesim.effects.atmosphere.sky.NightSky;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The atmosphere drawn for a test: a meshlet renderer of half-float color (scene units, unclamped), forward shading lit
 * by the sun and the moon as directional lights and by the captured sky ({@link EnvironmentLighting}), the
 * atmosphere's sky, aerial perspective and capture, and the scene's color read back after everything.
 */
final class AtmosphereRig implements AutoCloseable {

    final VulkanDevice device;
    final int width;
    final int height;
    final FrameSlots slots = new FrameSlots(2);
    final GeometryPool pool;
    final GpuScene scene;
    final MeshletRenderer renderer;
    final Lights lights;
    final Textures textures;
    final Materials materials;
    final FrameGraph graph;
    final Atmosphere atmosphere;
    final Environment environment;
    final SkyCapture capture;
    final Camera camera = new Camera();
    final SceneView view;
    final AtmosphereSky sky;
    final AerialPerspective aerial;
    final EnvironmentLighting lighting;
    private final TestTarget target;
    private final GpuBuffer pixels;
    private int instances;
    /** Whether the sun and the moon light the scene (the atmosphere's lights); off: the sky's light alone. */
    boolean lit = true;

    /** Materials: grey and rough, a mirror, black and rough (ids in the table). */
    final int grey;
    final int mirror;
    final int black;

    AtmosphereRig(VulkanDevice device, int width, int height) {
        this(device, width, height, true);
    }

    /** With the sky captured into the environment ({@code capture}), or the environment left black. */
    AtmosphereRig(VulkanDevice device, int width, int height, boolean captured) {
        this.device = device;
        this.width = width;
        this.height = height;
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 16, 2048, 16), slots);
        scene = new GpuScene(device, pool, 1024, slots);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT, slots);
        lights = new Lights(device, 4, 1, slots);
        textures = new Textures(device, slots);
        materials = new Materials(device, 4, slots);
        grey = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(0.5, 0.5, 0.5, 1))
            .withRoughness(1)));
        mirror = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(1, 1, 1, 1))
            .withRoughness(0).withMetallic(1)));
        black = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(0, 0, 0, 1))
            .withRoughness(1)));
        scene.setMaterials(materials);
        graph = new FrameGraph(device, slots);
        atmosphere = new Atmosphere(device, slots, graph.settings());
        environment = Environment.uniform("atmosphere", 0, 0, 0);
        capture = new SkyCapture(atmosphere, environment, textures);
        lights.directional(0, 0, -1, 0, 0, 0, 0);
        lights.directional(1, 0, -1, 0, 0, 0, 0);
        lights.setCount(2);
        camera.position(0, 2, 0).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), width, height, 0.1);
        ViewPort port = graph.addView("main").size(width, height);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 12, 1 << 18)).set(1, MeshletRenderer.MODE_LIT);
        sky = new AtmosphereSky(atmosphere, view);
        aerial = new AerialPerspective(atmosphere, view);
        lighting = new EnvironmentLighting(environment, view);
        target = new TestTarget(device, width, height, com.truesim.engine.vulkan.Format.R16G16B16A16_SFLOAT);
        pixels = GpuBuffer.hostVisible(device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0))
            .add(new ClusteredLights(lights, view)).add(lighting);
        if (captured) {
            port.add(capture);
        }
        port.add(sky).add(aerial)
            .add(new Capture(pixels, width, height)).output(Keys.SCENE_COLOR, target.output());
    }

    /** Adds {@code mesh} at ({@code x}, {@code y}, {@code z}) scaled by {@code scale}; returns its instance. */
    int add(MeshData mesh, double x, double y, double z, float scale) {
        return add(mesh, grey, x, y, z, scale);
    }

    /** Adds {@code mesh} of {@code material} at ({@code x}, {@code y}, {@code z}) scaled by {@code scale}. */
    int add(MeshData mesh, int material, double x, double y, double z, float scale) {
        int index;
        try (mesh) {
            index = pool.add(mesh);
        }
        int id = instances++;
        scene.set(id, index, material, x, y, z, 0, 0, 0, 1, scale, scale, scale);
        return id;
    }

    /** {@code count} frames of the sky {@code night} (null: the atmosphere's fixed sun), waited for. */
    void frames(NightSky night, int count) {
        for (int f = 0; f < count; f++) {
            int slot = graph.beginFrame();
            slots.begin(slot);
            atmosphere.update(night, camera.position(0), camera.position(1), camera.position(2));
            if (lit) {
                atmosphere.light(lights, 0, 1);
            }
            view.set(1, MeshletRenderer.MODE_LIT);
            graph.submit(0, 0);
            slots.end();
        }
        device.waitIdle();
    }

    /** The scene's color of the last frame, rgb per pixel, scene units. */
    float[][] read() {
        float[][] rgb = new float[3][width * height];
        MemorySegment memory = pixels.memory();
        for (int i = 0; i < width * height; i++) {
            for (int c = 0; c < 3; c++) {
                rgb[c][i] = Float.float16ToFloat(memory.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 2L * c));
            }
        }
        return rgb;
    }

    /** The pixel at ({@code x}, {@code y}) of {@code rgb}. */
    double[] pixel(float[][] rgb, int x, int y) {
        int i = y * width + x;
        return new double[] {rgb[0][i], rgb[1][i], rgb[2][i]};
    }

    @Override
    public void close() {
        device.waitIdle();
        graph.close();
        capture.close();
        atmosphere.close();
        target.close();
        pixels.close();
        renderer.close();
        scene.close();
        lights.close();
        scene.setMaterials(null);
        materials.close();
        textures.close();
        pool.close();
    }

    /** Copies the scene's color into {@code pixels} after everything drawn over it. */
    record Capture(GpuBuffer pixels, int width, int height) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("capture").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferRowLength(width).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                    region.imageExtent().set(width, height, 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }
}
