package com.truesim.effects.atmosphere.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.atmosphere.render.layout.LutPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Arrays;
import java.util.Objects;

/**
 * The atmosphere's tables that depend on its media alone (Hillaire 2020 Sec. 5.1 and 5.5): the transmittance table
 * (256 x 64 by default) and the multiple-scattering table (32 x 32), kept by the graph (persistent images) and made
 * again only when the media change ({@link Atmosphere#mediaVersion}) or the images are new. A shared request: every
 * view and every library (clouds) reading them gets the same two images.
 *
 * <pre>{@code
 * AtmosphereLuts.Tables tables = g.request(new AtmosphereLuts(atmosphere));
 * pass.read(tables.transmittance(), Access.SAMPLED_COMPUTE).read(tables.multipleScattering(), Access.SAMPLED_COMPUTE);
 * // shaders read them through the AtmosphereFrame at atmosphere.frameAddress(): atmosphere.slang's transmittance()
 * }</pre>
 *
 * @param atmosphere the atmosphere whose media they hold
 */
public record AtmosphereLuts(Atmosphere atmosphere) implements Request<AtmosphereLuts.Tables> {

    /** The format of the tables: half floats. */
    public static final Format FORMAT = Format.R16G16B16A16_SFLOAT;

    /** The two tables. */
    public record Tables(Image transmittance, Image multipleScattering) {
    }

    public AtmosphereLuts {
        Objects.requireNonNull(atmosphere, "atmosphere");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Tables build(GraphBuilder g) {
        int width = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("transmittanceWidth"));
        int height = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("transmittanceHeight"));
        int size = g.setting(AtmosphereSky.ATMOSPHERE.<Integer>setting("multipleScatteringSize"));
        Image transmittance = g.persistent("atmosphere transmittance (" + width + " x " + height + ")",
            ImageDesc.of(FORMAT, width, height));
        Image multiple = g.persistent("atmosphere multiple scattering (" + size + ")", ImageDesc.of(FORMAT, size,
            size));
        Pipeline transmittanceProgram = g.computeProgram("shaders/atmosphere/luts.spv", "transmittanceMain",
            LutPush.BYTES);
        Pipeline multipleProgram = g.computeProgram("shaders/atmosphere/luts.spv", "multipleScatteringMain",
            LutPush.BYTES);
        Rebuilds rebuilds = new Rebuilds();
        LutPush push = new LutPush();
        g.pass("atmosphere transmittance").write(transmittance, Access.STORAGE_WRITE).record(ctx -> {
            if (rebuilds.due(ctx, transmittance, atmosphere.mediaVersion())) {
                ctx.dispatch(transmittanceProgram, push.atmosphere(atmosphere.frameAddress())
                    .target(ctx.storage(transmittance)).size(width | height << 16), (width + 7) / 8,
                    (height + 7) / 8, 1);
            }
        });
        g.pass("atmosphere multiple scattering").read(transmittance, Access.SAMPLED_COMPUTE)
            .write(multiple, Access.STORAGE_WRITE).record(ctx -> {
                atmosphere.transmittanceSlot(ctx.sampled(transmittance));
                if (rebuilds.due(ctx, transmittance, atmosphere.mediaVersion())) {
                    ctx.dispatch(multipleProgram, push.atmosphere(atmosphere.frameAddress())
                        .target(ctx.storage(multiple)).size(size | size << 16), size, size, 1);
                }
            });
        return new Tables(transmittance, multiple);
    }

    /**
     * Whether a frame makes the tables: once per frame and image (the image's handle), when the image is new or holds
     * older media. Every pass asking in one frame gets the same answer. Allocates nothing once an image is seen.
     */
    static final class Rebuilds {

        private long[] images = new long[2];
        private long[] versions = new long[2];
        private long[] frames = new long[2];
        private boolean[] answers = new boolean[2];
        private int count;

        synchronized boolean due(PassContext ctx, Image image, long version) {
            long handle = ctx.unsafe().image(image);
            long frame = ctx.frame();
            int i = 0;
            while (i < count && images[i] != handle) {
                i++;
            }
            if (i == count) {
                add(handle);
            }
            if (frames[i] != frame) {
                answers[i] = ctx.fresh(image) || versions[i] != version;
                versions[i] = version;
                frames[i] = frame;
            }
            return answers[i];
        }

        @AllowAllocation("an image seen the first time")
        private void add(long handle) {
            if (count == images.length) {
                images = Arrays.copyOf(images, 2 * count);
                versions = Arrays.copyOf(versions, 2 * count);
                frames = Arrays.copyOf(frames, 2 * count);
                answers = Arrays.copyOf(answers, 2 * count);
            }
            images[count] = handle;
            versions[count] = 0;
            frames[count] = -1;
            count++;
        }
    }
}
