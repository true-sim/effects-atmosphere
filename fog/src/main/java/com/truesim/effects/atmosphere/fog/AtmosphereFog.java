package com.truesim.effects.atmosphere.fog;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.graph.Id;
import com.truesim.effects.atmosphere.fog.hooks.AtmosphereFogParameters;
import com.truesim.effects.atmosphere.fog.hooks.FogConstants;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookParameters;
import com.truesim.engine.render.ShadingHookSource;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * The fog of a view: the {@code atmosphere.fog} media hook (atmosphere/fog.slang) with the scene's values for it (the
 * outdoor height fog and its light, engine-scene's {@code ShadingHookComponent}) and, each frame, the fog regions
 * nearest the view's camera ({@link FogRegions}: fog volumes and indoor zones from the scene's components, at most
 * {@link FogSettings#MAX_VOLUMES} and {@link FogSettings#MAX_ZONES}, none farther than
 * {@link FogSettings#CULL_DISTANCE}), written camera-relative into a buffer of its own that the hook integrates every
 * view ray through. Shading anywhere (forward, the visibility buffer, transparency, particles, the sky) fogs by where
 * the ray runs, not by which object it ends on (effects-atmosphere#4).
 *
 * <pre>{@code
 * FogRegions regions = new FogRegions(scene);
 * port.add(new AtmosphereFog(main, regions));
 * scene.components().set(weather, ShadingHookComponent.TYPE, new ShadingHookComponent(AtmosphereFog.HOOK,
 *     MaterialData.builder(AtmosphereFog.HOOK).set("density", 0.02f).build().values(), true));
 * }</pre>
 *
 * <p>One per view (the graph refuses one instance in two); closing it frees its buffer.
 */
public final class AtmosphereFog implements Technique, AutoCloseable {

    /** The hook's name: its {@code [ShadingHook]}. */
    public static final String HOOK = "atmosphere.fog";
    /** The technique's id. */
    public static final Id<AtmosphereFog> ID = Id.of("truesim.atmosphere.fog", AtmosphereFog.class);

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int REGION = FogConstants.FOG_REGION_BYTES;
    /**
     * The record's {@code regions} address: the 8 bytes before {@code froxelFar} (the generator gives addresses no field
     * constant).
     */
    public static final long REGIONS_OFFSET = AtmosphereFogParameters.FROXEL_FAR.offset() - 8;

    private final SceneView view;
    private final FogRegions regions;
    private final VulkanDevice device;
    private FrameBuffer buffer;
    private int capacity;
    private EngineSettings settings;
    private boolean active = true;
    // The frame's choice: region indices and their distances, nearest first
    private int[] chosen = new int[0];
    private double[] distances = new double[0];
    private int chosenCount;
    private FogFroxels froxels;
    private FogLighting lighting;
    private final float[] sun = new float[3];
    private final float[] sunLight = new float[3];
    private final float[] ambient = new float[3];

    /**
     * What adds froxels to a view's fog (atmosphere-render's {@code VolumetricFog}): the fog integrated in the view's
     * frustum with every light and its shadows, which the hook reads up to their reach and continues from.
     */
    public interface FogFroxels {

        /**
         * Builds the froxels for the view building in {@code g}: the images shading samples (none: no froxels this
         * build). Called from the fog's build, which comes before the view's shading.
         */
        List<com.truesim.engine.graph.Image> build(GraphBuilder g, AtmosphereFog fog);

        /**
         * Writes the froxel fields of the fog's record ({@code froxels}, {@code froxelFar}, ...) at {@code offset} of
         * {@code into}, after the fog has written all of its own (the record complete: froxels may copy it).
         */
        void write(PassContext ctx, MemorySegment into, long offset);
    }

    /**
     * What lights the fog in place of the values content gives (atmosphere-render's {@code Atmosphere}): the sun's
     * direction (towards it) and illuminance, and the sky's isotropic radiance, each frame.
     */
    public interface FogLighting {

        /** Fills the sun's direction and illuminance and the ambient radiance (scene units). */
        void light(float[] sunDirection, float[] sunIlluminance, float[] ambient);
    }

    /** Takes froxels from {@code froxels} from the next build (null: none). */
    public AtmosphereFog froxels(FogFroxels froxels) {
        this.froxels = froxels;
        return this;
    }

    /** Lights the fog by {@code lighting} from the next frame (null: as content says). */
    public AtmosphereFog lighting(FogLighting lighting) {
        this.lighting = lighting;
        return this;
    }

    /** The view it fogs. */
    public SceneView view() {
        return view;
    }

    /** The settings the fog was last built with. */
    public EngineSettings settings() {
        return settings;
    }

    /** The fog of {@code view}, with the regions of {@code regions}. */
    public AtmosphereFog(SceneView view, FogRegions regions) {
        this.view = Objects.requireNonNull(view, "view");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.device = view.view().renderer().device();
    }

    @Override
    public Id<AtmosphereFog> id() {
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

    /** Turns the fog on or off from the next frame (its bit of the view's hook block: no rebuild). */
    public AtmosphereFog active(boolean on) {
        active = on;
        return this;
    }

    /** The regions it wrote last frame. */
    public int regionsWritten() {
        return chosenCount;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    @AllowImpure("keeps the settings and the region buffer it was built with: the hook's source writes through them")
    public void build(GraphBuilder g) {
        settings = g.settings();
        int wanted = Math.max(1, settings.get(FogSettings.MAX_VOLUMES) + settings.get(FogSettings.MAX_ZONES));
        if (buffer == null || capacity < wanted) {
            if (buffer != null) {
                view.port().own(buffer); // freed with the view: frames in flight may read it
            }
            capacity = wanted;
            buffer = FrameBuffer.create(device, (long) REGION * capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, view.view().renderer().scene().frameSlots());
        }
        List<com.truesim.engine.graph.Image> froxelImages = froxels == null ? List.of() : froxels.build(g, this);
        com.truesim.engine.render.ShadingHookInputs.Set inputs = ShadingHooks.of(device).add(g, HOOK,
            new ShadingHookSource() {
            @Override
            public int bytes() {
                return AtmosphereFogParameters.BYTES;
            }

            @Override
            public boolean active() {
                return active;
            }

            @Override
            public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                authored.write(into, offset);
                int written = writeRegions();
                into.set(I32, offset + AtmosphereFogParameters.REGION_COUNT.offset(), written);
                into.set(I32, offset + AtmosphereFogParameters.REGIONS_PER_RAY.offset(),
                    settings.get(FogSettings.REGIONS_PER_RAY));
                into.set(I32, offset + AtmosphereFogParameters.STEPS.offset(), settings.get(FogSettings.STEPS));
                into.set(I64, offset + REGIONS_OFFSET, buffer.deviceAddress());
                if (lighting != null) {
                    lighting.light(sun, sunLight, ambient);
                    put(into, offset + AtmosphereFogParameters.SUN_DIRECTION.offset(), sun);
                    put(into, offset + AtmosphereFogParameters.SUN_ILLUMINANCE.offset(), sunLight);
                    put(into, offset + AtmosphereFogParameters.AMBIENT.offset(), ambient);
                }
                if (froxels != null) {
                    froxels.write(ctx, into, offset);
                }
            }
        });
        for (int i = 0; i < froxelImages.size(); i++) {
            inputs.sample(froxelImages.get(i));
        }
    }

    private static void put(MemorySegment into, long offset, float[] v) {
        into.set(F32, offset, v[0]);
        into.set(F32, offset + 4, v[1]);
        into.set(F32, offset + 8, v[2]);
    }

    /** Chooses the regions nearest the camera, writes them camera-relative; returns how many. */
    private int writeRegions() {
        Camera camera = view.camera();
        double cx = camera.position(0);
        double cy = camera.position(1);
        double cz = camera.position(2);
        int count = regions.count();
        if (chosen.length < count) {
            grow(count);
        }
        int maxVolumes = settings.get(FogSettings.MAX_VOLUMES);
        int maxZones = settings.get(FogSettings.MAX_ZONES);
        double cull = settings.get(FogSettings.CULL_DISTANCE);
        // Every region in reach, sorted by distance (insertion: few regions, mostly in last frame's order)
        int n = 0;
        for (int r = 0; r < count; r++) {
            double d = regions.distance(r, cx, cy, cz);
            if (d > cull) {
                continue;
            }
            int j = n++;
            while (j > 0 && distances[j - 1] > d) {
                distances[j] = distances[j - 1];
                chosen[j] = chosen[j - 1];
                j--;
            }
            distances[j] = d;
            chosen[j] = r;
        }
        // The nearest of each kind, up to its cap, kept in that order
        int volumes = 0;
        int zones = 0;
        int kept = 0;
        for (int i = 0; i < n && kept < capacity; i++) {
            int r = chosen[i];
            if (regions.zone(r) ? zones++ >= maxZones : volumes++ >= maxVolumes) {
                continue;
            }
            write(kept++, r, cx, cy, cz);
        }
        chosenCount = kept;
        return kept;
    }

    @AllowAllocation("growing with the scene's regions")
    private void grow(int count) {
        chosen = new int[count * 2];
        distances = new double[count * 2];
    }

    /** Region {@code r} at slot {@code slot} of the buffer, relative to the camera at ({@code cx}, {@code cy}, {@code cz}). */
    private void write(int slot, int r, double cx, double cy, double cz) {
        long at = (long) slot * REGION;
        buffer.putFloat(at, (float) (regions.center(r, 0) - cx));
        buffer.putFloat(at + 4, (float) (regions.center(r, 1) - cy));
        buffer.putFloat(at + 8, (float) (regions.center(r, 2) - cz));
        buffer.putInt(at + 12, regions.kind(r));
        for (int axis = 0; axis < 3; axis++) {
            long row = at + 16 + 16L * axis;
            buffer.putFloat(row, (float) regions.axis(r, axis, 0));
            buffer.putFloat(row + 4, (float) regions.axis(r, axis, 1));
            buffer.putFloat(row + 8, (float) regions.axis(r, axis, 2));
        }
        buffer.putFloat(at + 28, regions.density(r));
        buffer.putFloat(at + 44, regions.falloff(r));
        buffer.putFloat(at + 60, regions.blendDistance(r));
        buffer.putFloat(at + 64, (float) regions.halfExtent(r, 0));
        buffer.putFloat(at + 68, (float) regions.halfExtent(r, 1));
        buffer.putFloat(at + 72, (float) regions.halfExtent(r, 2));
        buffer.putFloat(at + 76, regions.light(r));
        buffer.putFloat(at + 80, regions.albedo(r, 0));
        buffer.putFloat(at + 84, regions.albedo(r, 1));
        buffer.putFloat(at + 88, regions.albedo(r, 2));
        buffer.putFloat(at + 92, 0);
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
