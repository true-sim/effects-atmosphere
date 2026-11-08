package com.truesim.effects.atmosphere.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.atmosphere.render.layout.LutPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Request;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;

/**
 * What the atmosphere looks like from the camera this frame: the sky-view table (Hillaire 2020 Sec. 5.3: the sky's
 * radiance all around, 192 x 108 by default) and the aerial perspective volume (Sec. 5.4: the in-scattered light and
 * the transmittance from the camera to every distance up to {@link AtmosphereConfig#aerialRange}, by direction), made
 * every frame from the transmittance and multiple-scattering tables. A shared request: every view of the camera, every
 * eye and every library reading them gets the same images. Laid around the camera, not over a frustum (unlike the
 * paper's froxels), so one volume serves every view at the camera's position and points off screen too.
 *
 * <pre>{@code
 * SkyViewLut.Images sky = g.request(new SkyViewLut(atmosphere));
 * pass.read(sky.skyView(), Access.SAMPLED_COMPUTE);  // atmosphere.slang's skyViewUv, skyViewTexel
 * }</pre>
 *
 * @param atmosphere the atmosphere seen
 */
public record SkyViewLut(Atmosphere atmosphere) implements Request<SkyViewLut.Images> {

    /** The images' format: half floats. */
    public static final Format FORMAT = Format.R16G16B16A16_SFLOAT;

    /**
     * The frame's images: the sky-view table, and the aerial perspective's in-scattered light and transmittance
     * volumes (null without aerial perspective), with the tables they were made from.
     */
    public record Images(Image skyView, Image scattered, Image transmitted, AtmosphereLuts.Tables tables) {
    }

    public SkyViewLut {
        Objects.requireNonNull(atmosphere, "atmosphere");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Images build(GraphBuilder g) {
        AtmosphereLuts.Tables tables = g.request(new AtmosphereLuts(atmosphere));
        int width = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("skyViewWidth"));
        int height = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("skyViewHeight"));
        boolean aerial = g.setting(AtmosphereSky.ATMOSPHERE.<Boolean>setting("aerialPerspective"));
        int aw = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("aerialWidth"));
        int ah = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("aerialHeight"));
        int slices = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("aerialSlices"));
        Image skyView = g.create("atmosphere sky view", ImageDesc.of(FORMAT, width, height));
        Pipeline skyProgram = g.computeProgram("shaders/atmosphere/luts.spv", "skyViewMain", LutPush.BYTES);
        LutPush push = new LutPush();
        g.pass("atmosphere sky view").read(tables.transmittance(), Access.SAMPLED_COMPUTE)
            .read(tables.multipleScattering(), Access.SAMPLED_COMPUTE).write(skyView, Access.STORAGE_WRITE)
            .record(ctx -> {
                AtmosphereConfig c = atmosphere.config();
                atmosphere.tables(ctx.sampled(tables.transmittance()), ctx.sampled(tables.multipleScattering()));
                ctx.dispatch(skyProgram, push.atmosphere(atmosphere.frameAddress()).target(ctx.storage(skyView))
                    .size(width | height << 16).minSteps(c.minSteps()).maxSteps(c.maxSteps()).stepsPerKm(1 / 3f),
                    (width + 7) / 8, (height + 7) / 8, 1);
            });
        if (!aerial) {
            return new Images(skyView, null, null, tables);
        }
        ImageDesc volume = ImageDesc.of(FORMAT, aw, ah).volume(slices);
        Image scattered = g.create("atmosphere aerial perspective", volume);
        Image transmitted = g.create("atmosphere aerial transmittance", volume);
        Pipeline aerialProgram = g.computeProgram("shaders/atmosphere/luts.spv", "aerialMain", LutPush.BYTES);
        LutPush aerialPush = new LutPush();
        g.pass("atmosphere aerial perspective").read(tables.transmittance(), Access.SAMPLED_COMPUTE)
            .read(tables.multipleScattering(), Access.SAMPLED_COMPUTE).write(scattered, Access.STORAGE_WRITE)
            .write(transmitted, Access.STORAGE_WRITE).record(ctx -> {
                atmosphere.tables(ctx.sampled(tables.transmittance()), ctx.sampled(tables.multipleScattering()));
                ctx.dispatch(aerialProgram, aerialPush.atmosphere(atmosphere.frameAddress())
                    .target(ctx.storage(scattered)).target2(ctx.storage(transmitted)).size(aw | ah << 16)
                    .depth(slices).far(range()), (aw + 7) / 8, (ah + 7) / 8, 1);
            });
        return new Images(skyView, scattered, transmitted, tables);
    }

    /**
     * The aerial perspective's range this frame, km: the config's, or from above the atmosphere as far as the planet's
     * far horizon (the ground seen from orbit).
     */
    float range() {
        AtmosphereConfig c = atmosphere.config();
        double r = c.planetRadius() + atmosphere.cameraAltitudeKm();
        double horizon = Math.sqrt(Math.max(r * r - (double) c.planetRadius() * c.planetRadius(), 0));
        return (float) Math.max(c.aerialRange(), Math.min(horizon * 1.2, 20_000));
    }
}
