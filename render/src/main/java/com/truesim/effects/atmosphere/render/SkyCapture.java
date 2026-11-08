package com.truesim.effects.atmosphere.render;

import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.atmosphere.render.layout.LutPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * The sky captured into the engine's {@link Environment}: the atmosphere around the camera (without the discs of the
 * sun and the moon, which light the scene as its directional lights, and without stars) as an equirectangular
 * panorama in a dynamic texture of {@link Textures}, which the environment then takes as its panorama
 * ({@link Environment#setPanorama}): its filtering (the radiance cube, the GGX and sheen cubes, the diffuse spherical
 * harmonics) runs again, so environment lighting, reflections, the probe volume's and radiance cascades' rays that miss
 * and screen-space reflections' fallback see the real sky. Captured again only when the sun or the moon moved by
 * {@code captureAngle} or changed by {@code captureChange}, the camera climbed by {@code captureAltitude}, the media or
 * the exposure changed; the environment takes a capture the frame after it was made.
 *
 * <pre>{@code
 * Environment environment = Environment.uniform("sky", 0, 0, 0);
 * SkyCapture capture = new SkyCapture(atmosphere, environment, textures);
 * port.add(capture).add(new EnvironmentLighting(environment, view));
 * }</pre>
 *
 * <p>Added to the camera's view (it builds with the view, before the sky). Closing it frees its texture.
 */
public final class SkyCapture implements Technique, AutoCloseable {

    /** The technique's id. */
    public static final Id<SkyCapture> ID = Id.of("truesim.atmosphere.skyCapture", SkyCapture.class);

    private final Atmosphere atmosphere;
    private final Environment environment;
    private final Textures textures;
    private final LutPush push = new LutPush();
    private final VkMemoryBarrier2.Buffer barrier;
    private final VkDependencyInfo dependency;
    private int texture = -1;
    private int width;
    private long capturedLights = -1;
    private long capturedMedia = -1;
    private double capturedAltitude = Double.NaN;
    private boolean pending;
    private int captures;

    /** Captures {@code atmosphere}'s sky into {@code environment}, through a texture of {@code textures}. */
    public SkyCapture(Atmosphere atmosphere, Environment environment, Textures textures) {
        this.atmosphere = Objects.requireNonNull(atmosphere, "atmosphere");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.textures = Objects.requireNonNull(textures, "textures");
        // Every access before against every after: the panorama is written here and sampled by the environment's
        // filtering, which the graph does not know of (a bindless texture)
        barrier = VkMemoryBarrier2.calloc(1).sType$Default()
            .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
            .srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
            .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        dependency = VkDependencyInfo.calloc().sType$Default().pMemoryBarriers(barrier);
    }

    @Override
    public Id<SkyCapture> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    /**
     * The view's color, only to be built with the view (a technique providing nothing is never built): it does not
     * touch it.
     */
    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public Order order() {
        return Order.at(Order.SKY).before(AtmosphereSky.ID);
    }

    /** The panorama's texture ({@link Textures}), or -1 before the first build. */
    public int texture() {
        return texture;
    }

    /** How many captures were made. */
    public int captures() {
        return captures;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modify(Keys.SCENE_COLOR);
        int wanted = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("captureWidth"));
        if (texture < 0 || wanted != width) {
            if (texture >= 0) {
                textures.remove(texture);
            }
            width = wanted;
            texture = textures.dynamic(TextureFormat.RGBA16F, width, width / 2, 1, true);
            capturedLights = -1;
        }
        SkyViewLut.Images sky = g.request(new SkyViewLut(atmosphere));
        AtmosphereLuts.Tables tables = sky.tables();
        Pipeline program = g.computeProgram("shaders/atmosphere/luts.spv", "captureMain", LutPush.BYTES);
        g.pass("atmosphere sky capture").read(sky.skyView(), Access.SAMPLED_COMPUTE)
            .read(tables.transmittance(), Access.SAMPLED_COMPUTE)
            .read(tables.multipleScattering(), Access.SAMPLED_COMPUTE).sideEffects()
            .record(ctx -> record(ctx, program, sky));
    }

    private void record(PassContext ctx, Pipeline program, SkyViewLut.Images sky) {
        if (pending) {
            // Last frame's capture, its writes made visible by its barrier: filtered from now on
            environment.setPanorama(texture, width);
            pending = false;
        }
        AtmosphereConfig c = atmosphere.config();
        double altitude = atmosphere.cameraAltitudeKm() * 1000;
        boolean due = capturedLights != atmosphere.lightVersion() || capturedMedia != atmosphere.mediaVersion()
            || !(Math.abs(altitude - capturedAltitude) <= c.captureAltitude());
        if (!due) {
            return;
        }
        atmosphere.tables(ctx.sampled(sky.tables().transmittance()), ctx.sampled(sky.tables().multipleScattering()));
        boolean space = atmosphere.cameraAltitudeKm() > c.atmosphereHeight();
        Commands.pipelineBarrier2(ctx.unsafe().commands(), dependency.address());
        ctx.dispatch(program, push.atmosphere(atmosphere.frameAddress()).target(textures.storageSlot(texture))
            .size(width | (width / 2) << 16).sky(ctx.sampled(sky.skyView())).skySize(sky.skyView().desc().width()
                | sky.skyView().desc().height() << 16).perPixel(space ? 1 : 0).minSteps(c.minSteps())
            .maxSteps(c.maxSteps()).stepsPerKm(1 / 3f), (width + 7) / 8, (width / 2 + 7) / 8, 1);
        Commands.pipelineBarrier2(ctx.unsafe().commands(), dependency.address());
        capturedLights = atmosphere.lightVersion();
        capturedMedia = atmosphere.mediaVersion();
        capturedAltitude = altitude;
        pending = true;
        captures++;
    }

    @Override
    public void close() {
        if (texture >= 0) {
            textures.remove(texture);
            texture = -1;
        }
        dependency.free();
        barrier.free();
    }
}
