package com.truesim.effects.atmosphere.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.atmosphere.fog.AtmosphereFog;
import com.truesim.effects.atmosphere.fog.hooks.AtmosphereFogParameters;
import com.truesim.effects.atmosphere.render.layout.FroxelPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Objects;

/**
 * Volumetric fog in froxels for atmosphere-fog's fog (one integration: its media, its hook): the kind chosen for
 * {@link #SLOT} ({@code render.fog}). Handed to a view's {@link AtmosphereFog} ({@code fog.froxels(new
 * VolumetricFog(atmosphere, view))}), it samples the fog's media (height fog, fog volumes, indoor zones: the function
 * the hook integrates) in a grid over the view's frustum (Wronski 2014; Hillaire 2015): tiles of {@code tile} pixels,
 * {@code slices} slices from {@code near} exponentially to {@code far} metres along each ray; lights each froxel by
 * every light of the view's light list after its shadow (the sun's cascades: light shafts, the fog hook's known gap)
 * through the fog's Henyey-Greenstein phase, and by the sky's ambient light (the atmosphere's sky-view table, averaged
 * over the sphere); integrates each column front to back (Hillaire 2015's energy-conserving form). The fog's hook
 * reads the result for every point within the grid's reach on screen and integrates on from there as before (beyond
 * {@code far}, off screen: reflections, probes), so the near fog has shafts and the far fog stays exact.
 *
 * <ul>
 *   <li>{@link #FROXEL} ({@code froxel}, the default): the grid, 160 x 90 x 64 at 1080p's 12-pixel tiles;
 *   <li>{@link #ANALYTIC} ({@code analytic}): the fog hook alone (closed-form height fog, regions marched; no shadows);
 *   <li>{@code off}: the same as analytic for the fog (the hook stays the fog's to switch).
 * </ul>
 *
 * <p>Single-frame (no temporal reprojection yet: froxels at their centres, no jitter): a temporal pass (the design's
 * §8.4) is the follow-up. With an {@link Atmosphere}, the sky's ambient radiance is also read back to the CPU
 * ({@link Atmosphere#skyAmbient}) for the fog's own light beyond the grid.
 */
public final class VolumetricFog implements AtmosphereFog.FogFroxels {

    /** Where volumetric fog plugs in: {@code render.fog}, froxels by default. */
    public static final TechniqueSlot<VolumetricFog, Object> SLOT = TechniqueSlot.of("render.fog", "froxel", true,
        "volumetric fog: froxels with every light's shadows, or the fog hook's analytic integration alone",
        techniques -> techniques.register(VolumetricFog.SLOT, VolumetricFog.FROXEL)
            .register(VolumetricFog.SLOT, VolumetricFog.ANALYTIC));

    /**
     * The froxel kind's tunables (read when the graph builds: it rebuilds when they change).
     *
     * @param tile pixels a froxel covers across and down
     * @param slices slices along each ray
     * @param near metres: the first slice's end
     * @param far metres: the last slice's end, the grid's reach
     * @param lights whether the view's light list lights the fog (shadows included); off: the fog's own sun, unshadowed
     */
    public record Froxels(
        @Tunable(value = "pixels a fog froxel covers across and down", min = 2, max = 64) int tile,
        @Tunable(value = "fog froxel slices along each ray", min = 2, max = 256) int slices,
        @Tunable(value = "metres to the first fog froxel slice's end", min = 0.01, max = 100) float near,
        @Tunable(value = "metres the fog froxels reach along each ray", min = 1, max = 100_000) float far,
        @Tunable("whether every light of the view lights the fog, with its shadows") boolean lights)
        implements TechniqueConfig {
    }

    /** No tunables. */
    public record Analytic() implements TechniqueConfig {
    }

    /** The froxels: the default. */
    public static final TechniqueKind<Froxels, VolumetricFog, Object> FROXEL = TechniqueKind.of(SLOT, "froxel",
        new Froxels(12, 64, 0.5f, 300, true), "froxels over the view's frustum, every light with its shadows",
        (fog, config) -> fog);
    /** The fog hook alone. */
    public static final TechniqueKind<Analytic, VolumetricFog, Object> ANALYTIC = TechniqueKind.of(SLOT, "analytic",
        new Analytic(), "the fog hook's own integration: closed-form height fog, regions marched, no shadows",
        (fog, config) -> fog);

    /** The froxels' format: half floats. */
    public static final Format FORMAT = Format.R16G16B16A16_SFLOAT;

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED;

    private final Atmosphere atmosphere;
    private final SceneView view;
    private final FroxelPush ambientPush = new FroxelPush();
    private final FroxelPush lightPush = new FroxelPush();
    private final FroxelPush integratePush = new FroxelPush();
    private Image integrated;
    private float near;
    private float far;
    private int slices;

    /** Volumetric fog in {@code view}, the sky's ambient light from {@code atmosphere} (null: the fog's own). */
    public VolumetricFog(Atmosphere atmosphere, SceneView view) {
        this.atmosphere = atmosphere;
        this.view = Objects.requireNonNull(view, "view");
    }

    /** Registers the slot and its kinds' tunables in {@code registry}. */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public List<Image> build(GraphBuilder g, AtmosphereFog fog) {
        integrated = null;
        if (g.technique(SLOT) != FROXEL) {
            return List.of();
        }
        int tile = g.setting(FROXEL.<Integer>setting("tile"));
        slices = g.setting(FROXEL.<Integer>setting("slices"));
        near = g.setting(FROXEL.<Float>setting("near"));
        far = Math.max(g.setting(FROXEL.<Float>setting("far")), near * 2);
        boolean lit = g.setting(FROXEL.<Boolean>setting("lights"));
        ViewPort port = g.view();
        int pixelsWide = port.width();
        int pixelsHigh = port.height();
        int width = Math.max(1, (pixelsWide + tile - 1) / tile);
        int height = Math.max(1, (pixelsHigh + tile - 1) / tile);
        ImageDesc grid = ImageDesc.of(FORMAT, width, height).volume(slices);
        Image media = g.create("fog froxel media", grid);
        Image result = g.create("fog froxels", grid);
        Buffer ambient = g.createBuffer("fog sky ambient", 16);
        Buffer lights = lit ? g.optional(Keys.LIGHT_LIST) : null;
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(port));
        int record = ShadingHooks.of(view.view().renderer().device()).recordOffset(ShadingHooks.of(
            view.view().renderer().device()).require(AtmosphereFog.HOOK));
        int size = width | height << 16;
        int pixels = pixelsWide | pixelsHigh << 16;

        // The sky's ambient light: the sky-view table's mean, on the GPU, and back to the CPU for the far fog
        Pipeline ambientProgram = g.computeProgram("shaders/atmosphere/froxels.spv", "ambientMain", FroxelPush.BYTES);
        GraphBuilder.PassBuilder ambientPass = g.pass("fog sky ambient").write(ambient, BufferAccess.STORAGE_WRITE);
        SkyViewLut.Images sky = atmosphere == null ? null : g.request(new SkyViewLut(atmosphere));
        if (sky != null) {
            ambientPass.read(sky.skyView(), Access.SAMPLED_COMPUTE)
                .read(sky.tables().transmittance(), Access.SAMPLED_COMPUTE);
        }
        ambientPass.record(ctx -> {
            ambientPush.view(view.view().address()).ambient(ctx.address(ambient));
            if (sky != null) {
                ambientPush.atmosphere(atmosphere.frameAddress()).sky(ctx.sampled(sky.skyView()))
                    .skySize(sky.skyView().desc().width() | sky.skyView().desc().height() << 16);
            } else {
                ambientPush.atmosphere(ctx.address(ambient)).sky(0);
            }
            ctx.dispatch(ambientProgram, ambientPush, 1, 1, 1);
        });
        if (atmosphere != null) {
            g.readback("fog sky ambient readback", ambient, 0, 16, (data, frame) -> atmosphere.skyAmbient(
                data.get(F32, 0), data.get(F32, 4), data.get(F32, 8)));
        }

        // Each froxel's media and light
        Pipeline lightProgram = g.computeProgram("shaders/atmosphere/froxels.spv", "lightMain", FroxelPush.BYTES);
        GraphBuilder.PassBuilder lightPass = g.pass("fog froxel light").read(ambient, BufferAccess.STORAGE_READ)
            .write(media, Access.STORAGE_WRITE);
        if (lights != null) {
            lightPass.read(lights, BufferAccess.STORAGE_READ);
            shadows.readBy(lightPass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        }
        lightPass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            common(lightPush, ctx, size, pixels, record).ambient(ctx.address(ambient))
                .lights(lights != null ? ctx.address(lights) : ctx.address(ambient)).lit(lights != null ? 1 : 0)
                .media(ctx.storage(media));
            ctx.dispatch(lightProgram, lightPush, (width + 3) / 4, (height + 3) / 4, (slices + 3) / 4);
        });

        // Each column integrated
        Pipeline integrateProgram = g.computeProgram("shaders/atmosphere/froxels.spv", "integrateMain",
            FroxelPush.BYTES);
        g.pass("fog froxel integration").read(media, Access.STORAGE_READ).write(result, Access.STORAGE_WRITE)
            .record(ctx -> {
                common(integratePush, ctx, size, pixels, record).media(ctx.storage(media))
                    .integrated(ctx.storage(result));
                ctx.dispatch(integrateProgram, integratePush, (width + 7) / 8, (height + 7) / 8, 1);
            });
        integrated = result;
        return List.of(result);
    }

    private FroxelPush common(FroxelPush push, PassContext ctx, int size, int pixels, int record) {
        double base = view.camera().position(1) - fogBaseHeight();
        return push.view(view.view().address()).fogRecord(record).size(size).slices(slices).pixels(pixels).near(near)
            .far(far).cameraHeight((float) base);
    }

    /** The fog's base height: the authored record's, as the hook reads it (written in the view's block). */
    private double fogBaseHeight() {
        return baseHeight;
    }

    private volatile float baseHeight;

    @Override
    public void write(PassContext ctx, MemorySegment into, long offset) {
        baseHeight = into.get(F32, offset + AtmosphereFogParameters.BASE_HEIGHT.offset());
        if (integrated == null) {
            into.set(F32, offset + AtmosphereFogParameters.FROXEL_FAR.offset(), 0f);
            return;
        }
        into.set(I32, offset + AtmosphereFogParameters.FROXELS.offset(), ctx.sampled(integrated));
        into.set(F32, offset + AtmosphereFogParameters.FROXEL_FAR.offset(), far);
        into.set(F32, offset + AtmosphereFogParameters.FROXEL_NEAR.offset(), near);
        into.set(I32, offset + AtmosphereFogParameters.FROXEL_SLICES.offset(), slices);
        float[] m = view.camera().viewProjection();
        row(into, offset + AtmosphereFogParameters.FROXEL_X.offset(), m, 0);
        row(into, offset + AtmosphereFogParameters.FROXEL_Y.offset(), m, 1);
        row(into, offset + AtmosphereFogParameters.FROXEL_W.offset(), m, 3);
    }

    /** Row {@code r} of the column-major matrix {@code m}. */
    private static void row(MemorySegment into, long offset, float[] m, int r) {
        for (int c = 0; c < 4; c++) {
            into.set(F32, offset + 4L * c, m[4 * c + r]);
        }
    }
}
