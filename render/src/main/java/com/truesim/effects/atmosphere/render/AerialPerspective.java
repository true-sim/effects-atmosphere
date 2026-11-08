package com.truesim.effects.atmosphere.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.common.math.Maths;
import com.truesim.effects.atmosphere.render.hooks.AerialPerspectiveParameters;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookParameters;
import com.truesim.engine.render.ShadingHookSource;
import com.truesim.engine.render.ShadingHooks;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Objects;

/**
 * Aerial perspective in a view: the {@code atmosphere.aerialPerspective} media hook (atmosphere/aerial.slang) added to
 * the view's shading, reading the frame's aerial perspective volume ({@link SkyViewLut}), so every shaded point (opaque,
 * glass, particles, reflections' and probes' hits) takes the atmosphere between it and the camera once (Hillaire 2020
 * Sec. 5.4): its in-scattered light (sun, moon, multiple scattering, the planet's shadow) and transmittance. The hook
 * runs before other media hooks ({@code atmosphere.fog}: the far media first, then the near ones over them). The sky's
 * own pixels take the atmosphere from {@link AtmosphereSky}; this hook leaves them alone.
 *
 * <pre>{@code
 * port.add(new AerialPerspective(atmosphere, view));
 * }</pre>
 *
 * <p>Off with {@code render.sky.atmosphere.aerialPerspective=false} (its bit of the view's hooks: no rebuild of the
 * view's pipelines). One per view.
 */
public final class AerialPerspective implements Technique {

    /** The hook's name: its {@code [ShadingHook]}. */
    public static final String HOOK = "atmosphere.aerialPerspective";
    /** The technique's id. */
    public static final Id<AerialPerspective> ID = Id.of("truesim.atmosphere.aerialPerspective",
        AerialPerspective.class);

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED;

    private final Atmosphere atmosphere;
    private final SceneView view;
    private final double[] up = new double[3];
    private final double[] north = new double[3];
    private final double[] east = new double[3];

    /** The aerial perspective of {@code atmosphere} in {@code view}. */
    public AerialPerspective(Atmosphere atmosphere, SceneView view) {
        this.atmosphere = Objects.requireNonNull(atmosphere, "atmosphere");
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public Id<AerialPerspective> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    /** It adds its hook to the view's: built before the view's shading. */
    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SHADING_HOOKS);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        SkyViewLut request = new SkyViewLut(atmosphere);
        SkyViewLut.Images sky = g.request(request);
        if (sky.scattered() == null) {
            return; // off: no volume, no hook
        }
        int width = sky.scattered().desc().width();
        int height = sky.scattered().desc().height();
        int slices = sky.scattered().desc().depth();
        ShadingHooks.of(view.view().renderer().device()).add(g, HOOK, new ShadingHookSource() {
            @Override
            public int bytes() {
                return AerialPerspectiveParameters.BYTES;
            }

            @Override
            public boolean active() {
                return atmosphere.config().aerialPerspective();
            }

            @Override
            public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                frame(up, north, east);
                put(into, offset + AerialPerspectiveParameters.UP.offset(), up);
                into.set(F32, offset + AerialPerspectiveParameters.BETA.offset(), (float) beta());
                put(into, offset + AerialPerspectiveParameters.NORTH.offset(), north);
                into.set(F32, offset + AerialPerspectiveParameters.FAR.offset(), request.range());
                put(into, offset + AerialPerspectiveParameters.EAST.offset(), east);
                into.set(I32, offset + AerialPerspectiveParameters.SLICES.offset(), slices);
                into.set(I32, offset + AerialPerspectiveParameters.SCATTERED.offset(), ctx.sampled(sky.scattered()));
                into.set(I32, offset + AerialPerspectiveParameters.TRANSMITTED.offset(),
                    ctx.sampled(sky.transmitted()));
                into.set(I32, offset + AerialPerspectiveParameters.WIDTH.offset(), width);
                into.set(I32, offset + AerialPerspectiveParameters.HEIGHT.offset(), height);
            }
        }).sample(sky.scattered()).sample(sky.transmitted());
    }

    private static void put(MemorySegment into, long offset, double[] v) {
        into.set(F32, offset, (float) v[0]);
        into.set(F32, offset + 4, (float) v[1]);
        into.set(F32, offset + 8, (float) v[2]);
    }

    /** The camera's position from the planet's centre, km (atmosphere.slang's AtmosphereFrame.camera). */
    private double cameraKm(int axis, double r0) {
        return axis == 1 ? r0 + atmosphere.cameraY() * 0.001 : (axis == 0 ? atmosphere.cameraX() : atmosphere.cameraZ())
            * 0.001;
    }

    /** The sky frame at the camera (atmosphere.slang's skyFrame, in double precision). */
    private void frame(double[] upOut, double[] northOut, double[] eastOut) {
        double r0 = atmosphere.config().planetRadius() + atmosphere.originAltitude() * 0.001;
        double x = cameraKm(0, r0);
        double y = cameraKm(1, r0);
        double z = cameraKm(2, r0);
        double r = Math.sqrt(x * x + y * y + z * z);
        upOut[0] = x / r;
        upOut[1] = y / r;
        upOut[2] = z / r;
        // North: world -z made horizontal
        double nx = -upOut[0] * -upOut[2];
        double ny = -upOut[1] * -upOut[2];
        double nz = -1 - upOut[2] * -upOut[2];
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-4) {
            nx = 1 - upOut[0] * upOut[0];
            ny = -upOut[1] * upOut[0];
            nz = -upOut[2] * upOut[0];
            nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        northOut[0] = nx / nl;
        northOut[1] = ny / nl;
        northOut[2] = nz / nl;
        // East: north x up
        eastOut[0] = northOut[1] * upOut[2] - northOut[2] * upOut[1];
        eastOut[1] = northOut[2] * upOut[0] - northOut[0] * upOut[2];
        eastOut[2] = northOut[0] * upOut[1] - northOut[1] * upOut[0];
    }

    /** The horizon's depression at the camera, radians (atmosphere.slang's skyFrame). */
    private double beta() {
        double bottom = atmosphere.config().planetRadius();
        double r = Math.max(bottom + Math.max(atmosphere.cameraAltitudeKm(), 0.0005), bottom);
        double horizon = Math.sqrt(Math.max(r * r - bottom * bottom, 0));
        return Maths.acos(Maths.clamp(horizon / r, -1, 1));
    }
}
