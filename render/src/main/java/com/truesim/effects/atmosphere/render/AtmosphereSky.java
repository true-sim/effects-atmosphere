package com.truesim.effects.atmosphere.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.atmosphere.render.layout.AtmosphereSkyPush;
import com.truesim.effects.atmosphere.render.layout.GradientPush;
import com.truesim.effects.atmosphere.render.layout.StarsPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookInputs;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Pipeline;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The sky behind a view's scene, as the kind chosen for {@link #SLOT} ({@code render.sky}) draws it: over every pixel
 * no surface covers, in place of the engine's {@code SkyBackground}.
 *
 * <ul>
 *   <li>{@link #ATMOSPHERE} ({@code atmosphere}, the default; Hillaire 2020): the sky-view table (or each pixel's ray
 *   integrated: {@code perPixel}, and always from above the atmosphere), the sun's limb-darkened disc, the moon's disc
 *   lit by the sun (Lommel-Seeliger, its phase from the ephemeris), the stars of the star field, airglow and the Milky
 *   Way, all through the atmosphere's transmittance; the planet below the horizon, lit, from high up;
 *   <li>{@link #GRADIENT} ({@code gradient}): two colours by height dimmed by the sun's height towards a night colour,
 *   and the sun's disc: no tables, for games not aiming at realism;
 *   <li>{@code off}: nothing (the scene's clear colour), or the engine's {@code SkyBackground} added instead.
 * </ul>
 *
 * <p>Then the view's other media hooks (fog in front of the sky) take the sky as a point at infinity. It modifies
 * {@link Keys#SCENE_COLOR} at {@link Order#SKY}, before transparency, so glass shows it; per view, within its render
 * region. One per view.
 *
 * <pre>{@code
 * port.add(new AtmosphereSky(atmosphere, view)).add(new AerialPerspective(atmosphere, view));
 * AtmosphereSky.SLOT.choose(settings, AtmosphereSky.GRADIENT);                // render.sky=gradient
 * settings.set(AtmosphereSky.ATMOSPHERE.setting("ev100"), 12f);              // render.sky.atmosphere.ev100=12
 * }</pre>
 */
public final class AtmosphereSky implements Technique {

    /** The id of a view's sky. */
    public static final Id<AtmosphereSky> ID = Id.of("truesim.atmosphere.sky", AtmosphereSky.class);

    /** Where the sky plugs in: {@code render.sky}, the atmosphere by default. */
    public static final TechniqueSlot<AtmosphereSky, Technique> SLOT = TechniqueSlot.of("render.sky", "atmosphere",
        true, "the sky: off, the atmosphere (Hillaire 2020), or a gradient of two colours",
        techniques -> techniques.register(AtmosphereSky.SLOT, AtmosphereSky.ATMOSPHERE)
            .register(AtmosphereSky.SLOT, AtmosphereSky.GRADIENT));

    /**
     * The gradient kind's tunables: colours as luminance in cd/m² (scene units through the atmosphere's exposure).
     *
     * @param zenithRed the day sky's radiance straight up, red
     * @param zenithGreen green
     * @param zenithBlue blue
     * @param horizonRed at the horizon, red
     * @param horizonGreen green
     * @param horizonBlue blue
     * @param nightRed the night sky's, red
     * @param nightGreen green
     * @param nightBlue blue
     */
    public record Gradient(
        @Tunable(value = "the day sky's luminance straight up, red, cd/m²", min = 0, max = 1e6) float zenithRed,
        @Tunable(value = "the day sky's luminance straight up, green, cd/m²", min = 0, max = 1e6) float zenithGreen,
        @Tunable(value = "the day sky's luminance straight up, blue, cd/m²", min = 0, max = 1e6) float zenithBlue,
        @Tunable(value = "the day sky's luminance at the horizon, red, cd/m²", min = 0, max = 1e6) float horizonRed,
        @Tunable(value = "the day sky's luminance at the horizon, green, cd/m²", min = 0, max = 1e6)
        float horizonGreen,
        @Tunable(value = "the day sky's luminance at the horizon, blue, cd/m²", min = 0, max = 1e6)
        float horizonBlue,
        @Tunable(value = "the night sky's luminance, red, cd/m²", min = 0, max = 1e6) float nightRed,
        @Tunable(value = "the night sky's luminance, green, cd/m²", min = 0, max = 1e6) float nightGreen,
        @Tunable(value = "the night sky's luminance, blue, cd/m²", min = 0, max = 1e6) float nightBlue)
        implements TechniqueConfig {
    }

    /** The atmosphere of Hillaire 2020: the default. */
    public static final TechniqueKind<AtmosphereConfig, AtmosphereSky, Technique> ATMOSPHERE = TechniqueKind.of(SLOT,
        "atmosphere", AtmosphereConfig.EARTH, "Hillaire 2020's atmosphere: transmittance, multiple-scattering and "
            + "sky-view tables, the sun, the moon and the stars", (sky, config) -> sky.new Lit());
    /** Two colours by height: the cheap tier. */
    public static final TechniqueKind<Gradient, AtmosphereSky, Technique> GRADIENT = TechniqueKind.of(SLOT, "gradient",
        new Gradient(2000, 4000, 9000, 6000, 7500, 9000, 0.0005f, 0.0007f, 0.0012f),
        "two colours by height, dimmed by the sun's height: no atmosphere tables", (sky, config) -> sky.new Flat(
            config));

    private final Atmosphere atmosphere;
    private final SceneView view;
    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>();

    /** The sky of {@code atmosphere} behind {@code view}'s scene. */
    public AtmosphereSky(Atmosphere atmosphere, SceneView view) {
        this.atmosphere = Objects.requireNonNull(atmosphere, "atmosphere");
        this.view = Objects.requireNonNull(view, "view");
    }

    /** Registers the slot and its kinds' tunables in {@code registry}, for consoles and options screens. */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT);
    }

    @Override
    public Id<AtmosphereSky> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    /** The scene's depth as the opaque scene left it (read in its place, as the engine's background does). */
    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    @Override
    public Order order() {
        return Order.at(Order.SKY);
    }

    public Atmosphere atmosphere() {
        return atmosphere;
    }

    public SceneView view() {
        return view;
    }

    /** The chosen kind's technique, built in this one's place. */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        TechniqueKind<?, AtmosphereSky, Technique> kind = g.technique(SLOT);
        if (kind == null) {
            g.modify(Keys.SCENE_COLOR);
            return;
        }
        made.computeIfAbsent(kind, k -> make(kind)).build(g);
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, AtmosphereSky, Technique> kind) {
        return kind.create(this, () -> kind.config(view.port().settings()));
    }

    /** The pass writing over the sky's pixels, with the view's media hooks and region. */
    @AllowAllocation("building the graph, when it changes")
    private GraphBuilder.PassBuilder skyPass(GraphBuilder g, String name, Image color, Image depth,
        ShadingHookInputs.Set hooks) {
        GraphBuilder.PassBuilder pass = g.pass(name).read(depth, Access.SAMPLED_COMPUTE).write(color,
            Access.STORAGE_WRITE);
        hooks.readBy(pass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        ViewPort region = g.view();
        if (region != null) {
            pass.region(region);
        }
        return pass;
    }

    /** The atmosphere kind. */
    private final class Lit implements Technique {

        private final AtmosphereSkyPush push = new AtmosphereSkyPush();
        private final StarsPush starsPush = new StarsPush();

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            Image depth = g.read(Keys.SCENE_DEPTH);
            SkyViewLut.Images sky = g.request(new SkyViewLut(atmosphere));
            AtmosphereLuts.Tables tables = sky.tables();
            ShadingHookInputs.Set hooks = ShadingHookInputs.of(g, view.view());
            Pipeline program = g.computeProgram("shaders/atmosphere/sky.spv", "skyMain", AtmosphereSkyPush.BYTES,
                hooks.specialization());
            boolean starsOn = g.setting(ATMOSPHERE.<Boolean>setting("stars"));
            Buffer stars = g.createBuffer("atmosphere stars", Math.max(12L * color.desc().width()
                * color.desc().height(), 16));
            if (starsOn) {
                g.pass("atmosphere stars clear").write(stars, BufferAccess.TRANSFER_DST).record(ctx -> {
                    if (atmosphere.starCount() > 0) {
                        ctx.fill(stars, 0);
                    }
                });
                Pipeline splat = g.computeProgram("shaders/atmosphere/stars.spv", "starsMain", StarsPush.BYTES);
                GraphBuilder.PassBuilder pass = g.pass("atmosphere stars").write(stars, BufferAccess.STORAGE_WRITE);
                ViewPort region = g.view();
                if (region != null) {
                    pass.region(region);
                }
                pass.record(ctx -> {
                    int count = atmosphere.starCount();
                    if (count == 0) {
                        return;
                    }
                    int width = ctx.regionWidth(color);
                    int height = ctx.regionHeight(color);
                    starsPush.view(view.view().address()).stars(ctx.address(stars)).field(atmosphere.starsAddress())
                        .count(count).size(width | height << 16);
                    starsPush.equatorialX((float) atmosphere.worldFromEquatorial(0, 0),
                        (float) atmosphere.worldFromEquatorial(0, 1), (float) atmosphere.worldFromEquatorial(0, 2));
                    starsPush.equatorialY((float) atmosphere.worldFromEquatorial(1, 0),
                        (float) atmosphere.worldFromEquatorial(1, 1), (float) atmosphere.worldFromEquatorial(1, 2));
                    starsPush.equatorialZ((float) atmosphere.worldFromEquatorial(2, 0),
                        (float) atmosphere.worldFromEquatorial(2, 1), (float) atmosphere.worldFromEquatorial(2, 2));
                    ctx.dispatch(splat, starsPush, (count + 63) / 64, 1, 1);
                });
            }
            GraphBuilder.PassBuilder pass = skyPass(g, "atmosphere sky", color, depth, hooks)
                .read(sky.skyView(), Access.SAMPLED_COMPUTE).read(tables.transmittance(), Access.SAMPLED_COMPUTE)
                .read(tables.multipleScattering(), Access.SAMPLED_COMPUTE).read(stars, BufferAccess.STORAGE_READ);
            pass.record(ctx -> record(ctx, program, color, depth, sky, stars));
        }

        private void record(PassContext ctx, Pipeline program, Image color, Image depth, SkyViewLut.Images sky,
            Buffer stars) {
            AtmosphereConfig c = atmosphere.config();
            atmosphere.tables(ctx.sampled(sky.tables().transmittance()), ctx.sampled(sky.tables()
                .multipleScattering()));
            int width = ctx.regionWidth(color);
            int height = ctx.regionHeight(color);
            push.view(view.view().address()).atmosphere(atmosphere.frameAddress()).stars(ctx.address(stars))
                .color(ctx.storage(color)).depth(ctx.sampled(depth)).size(width | height << 16)
                .sky(ctx.sampled(sky.skyView())).skySize(sky.skyView().desc().width()
                    | sky.skyView().desc().height() << 16).perPixel(c.perPixel() ? 1 : 0)
                .starsOn(c.stars() && atmosphere.starCount() > 0 ? 1 : 0)
                .discs((c.sunDisc() ? 1 : 0) | (c.moonDisc() ? 2 : 0)).sceneUnitsPerLux((float) atmosphere.sceneUnits())
                .minSteps(c.minSteps()).maxSteps(c.maxSteps()).stepsPerKm(1 / 3f)
                .moonRadius((float) atmosphere.lightRadius(1)).moonRadiance((float) atmosphere.moonDiscFactor());
            atmosphere.moonDirection(scratch);
            push.moonDirection((float) scratch[0], (float) scratch[1], (float) scratch[2]);
            atmosphere.sunDirection(scratch);
            push.moonSun((float) scratch[0], (float) scratch[1], (float) scratch[2]);
            ctx.dispatch(program, push, (width + 7) / 8, (height + 7) / 8, 1);
        }

        private final double[] scratch = new double[3];
    }

    /** The gradient kind. */
    private final class Flat implements Technique {

        private final java.util.function.Supplier<Gradient> config;
        private final GradientPush push = new GradientPush();

        Flat(java.util.function.Supplier<Gradient> config) {
            this.config = config;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            Image depth = g.read(Keys.SCENE_DEPTH);
            ShadingHookInputs.Set hooks = ShadingHookInputs.of(g, view.view());
            Pipeline program = g.computeProgram("shaders/atmosphere/gradient.spv", "gradientMain",
                GradientPush.BYTES, hooks.specialization());
            skyPass(g, "atmosphere gradient sky", color, depth, hooks).record(ctx -> {
                Gradient c = config.get();
                float units = (float) atmosphere.sceneUnits();
                int width = ctx.regionWidth(color);
                int height = ctx.regionHeight(color);
                push.view(view.view().address()).atmosphere(atmosphere.frameAddress()).color(ctx.storage(color))
                    .depth(ctx.sampled(depth)).size(width | height << 16)
                    .discs(atmosphere.config().sunDisc() ? 1 : 0)
                    .zenithColor(c.zenithRed() * units, c.zenithGreen() * units, c.zenithBlue() * units)
                    .horizonColor(c.horizonRed() * units, c.horizonGreen() * units, c.horizonBlue() * units)
                    .nightColor(c.nightRed() * units, c.nightGreen() * units, c.nightBlue() * units);
                ctx.dispatch(program, push, (width + 7) / 8, (height + 7) / 8, 1);
            });
        }
    }
}
