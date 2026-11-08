package com.truesim.effects.atmosphere.fog;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.effects.atmosphere.fog.hooks.FogConstants;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * The scene's fog regions in world terms ({@link FogVolume} and {@link IndoorZone} components on shown nodes, placed by
 * their nodes' world transforms), gathered by {@link #update} each frame after the scene's update; the fog technique
 * ({@link AtmosphereFog}) takes the nearest of them for each view. One instance serves every view of a scene. One
 * thread; allocates nothing per frame but when the regions outgrow their arrays.
 *
 * <pre>{@code
 * FogRegions regions = new FogRegions(scene);
 * port.add(new AtmosphereFog(main, regions));
 * // each frame, after scene.update(), before the frame records:
 * regions.update();
 * }</pre>
 */
public final class FogRegions {

    /** Doubles per region: centre (3), axes (9, unit, world: x then y then z), half extents (3, scaled). */
    private static final int DOUBLES = 15;
    /** Floats per region: density, falloff, blend, light, albedo (3). */
    private static final int FLOATS = 7;

    private final SceneGraph scene;
    private final ComponentStore<FogVolume> volumes;
    private final ComponentStore<IndoorZone> zones;
    private double[] geometry = new double[16 * DOUBLES];
    private float[] media = new float[16 * FLOATS];
    private int[] kind = new int[16];
    private double[] radius = new double[16];
    private int count;

    /** The regions of {@code scene}'s components. */
    public FogRegions(SceneGraph scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.volumes = scene.components().store(FogVolume.TYPE);
        this.zones = scene.components().store(IndoorZone.TYPE);
    }

    /** Gathers the regions as the scene is now (its world transforms as of its last update). */
    public void update() {
        count = 0;
        TransformArray world = scene.worldTransforms();
        for (int i = 0; i < volumes.size(); i++) {
            long node = volumes.node(i);
            if (scene.layers(node) == 0) {
                continue;
            }
            FogVolume v = volumes.value(i);
            int r = add(world, node, v.shape(), FogConstants.FOG_VOLUME, v.halfExtents().x(), v.halfExtents().y(),
                v.halfExtents().z());
            int m = r * FLOATS;
            media[m] = v.density();
            media[m + 1] = v.falloff();
            media[m + 2] = v.blendDistance();
            media[m + 3] = 1;
            media[m + 4] = (float) v.albedo().x();
            media[m + 5] = (float) v.albedo().y();
            media[m + 6] = (float) v.albedo().z();
        }
        for (int i = 0; i < zones.size(); i++) {
            long node = zones.node(i);
            if (scene.layers(node) == 0) {
                continue;
            }
            IndoorZone z = zones.value(i);
            int role = z.mode() == IndoorZone.Mode.REPLACE ? FogConstants.FOG_REPLACE : FogConstants.FOG_EXCLUDE;
            int r = add(world, node, z.shape(), role, z.halfExtents().x(), z.halfExtents().y(), z.halfExtents().z());
            int m = r * FLOATS;
            media[m] = z.mode() == IndoorZone.Mode.REPLACE ? z.density() : 0;
            media[m + 1] = 0;
            media[m + 2] = z.blendDistance();
            media[m + 3] = z.light();
            media[m + 4] = (float) z.albedo().x();
            media[m + 5] = (float) z.albedo().y();
            media[m + 6] = (float) z.albedo().z();
        }
    }

    /** Adds a region of the node's world transform; returns its index. */
    private int add(TransformArray world, long node, FogShape shape, int role, double hx, double hy, double hz) {
        if (count == kind.length) {
            grow();
        }
        int r = count++;
        MemorySegment s = world.segment();
        long at = NodeHandle.index(node) * world.stride();
        double tx = s.get(ValueLayout.JAVA_DOUBLE, at);
        double ty = s.get(ValueLayout.JAVA_DOUBLE, at + 8);
        double tz = s.get(ValueLayout.JAVA_DOUBLE, at + 16);
        double qx = s.get(ValueLayout.JAVA_DOUBLE, at + 24);
        double qy = s.get(ValueLayout.JAVA_DOUBLE, at + 32);
        double qz = s.get(ValueLayout.JAVA_DOUBLE, at + 40);
        double qw = s.get(ValueLayout.JAVA_DOUBLE, at + 48);
        double sx = Math.abs(s.get(ValueLayout.JAVA_DOUBLE, at + 56));
        double sy = Math.abs(s.get(ValueLayout.JAVA_DOUBLE, at + 64));
        double sz = Math.abs(s.get(ValueLayout.JAVA_DOUBLE, at + 72));
        double n = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw); // the scene refuses rotations of length 0
        qx /= n;
        qy /= n;
        qz /= n;
        qw /= n;
        int g = r * DOUBLES;
        geometry[g] = tx;
        geometry[g + 1] = ty;
        geometry[g + 2] = tz;
        // The rotation's columns: the node's x, y and z axes in the world
        geometry[g + 3] = 1 - 2 * (qy * qy + qz * qz);
        geometry[g + 4] = 2 * (qx * qy + qz * qw);
        geometry[g + 5] = 2 * (qx * qz - qy * qw);
        geometry[g + 6] = 2 * (qx * qy - qz * qw);
        geometry[g + 7] = 1 - 2 * (qx * qx + qz * qz);
        geometry[g + 8] = 2 * (qy * qz + qx * qw);
        geometry[g + 9] = 2 * (qx * qz + qy * qw);
        geometry[g + 10] = 2 * (qy * qz - qx * qw);
        geometry[g + 11] = 1 - 2 * (qx * qx + qy * qy);
        double ex = Math.max(hx * sx, 1e-6);
        double ey = Math.max(hy * sy, 1e-6);
        double ez = Math.max(hz * sz, 1e-6);
        geometry[g + 12] = ex;
        geometry[g + 13] = ey;
        geometry[g + 14] = ez;
        kind[r] = (shape == FogShape.ELLIPSOID ? FogConstants.FOG_ELLIPSOID : FogConstants.FOG_BOX) | role << 4;
        radius[r] = Math.sqrt(ex * ex + ey * ey + ez * ez);
        return r;
    }

    @AllowAllocation("growing with the scene's regions")
    private void grow() {
        int size = kind.length * 2;
        geometry = java.util.Arrays.copyOf(geometry, size * DOUBLES);
        media = java.util.Arrays.copyOf(media, size * FLOATS);
        kind = java.util.Arrays.copyOf(kind, size);
        radius = java.util.Arrays.copyOf(radius, size);
    }

    /** How many regions there are, volumes first, then zones. */
    public int count() {
        return count;
    }

    /** Region {@code r}'s shape and role, as the shader reads them ({@code shape | role << 4}, FogConstants). */
    public int kind(int r) {
        return kind[r];
    }

    /** Whether region {@code r} is an indoor zone (else a fog volume). */
    public boolean zone(int r) {
        return kind[r] >> 4 != FogConstants.FOG_VOLUME;
    }

    /** Region {@code r}'s centre along {@code axis}, world. */
    public double center(int r, int axis) {
        return geometry[r * DOUBLES + axis];
    }

    /** Component {@code i} (0 to 2) of region {@code r}'s axis {@code axis} (0 x, 1 y, 2 z), world, unit. */
    public double axis(int r, int axis, int i) {
        return geometry[r * DOUBLES + 3 + 3 * axis + i];
    }

    /** Region {@code r}'s half extent (radius) along its axis {@code axis}, metres, scaled by its node. */
    public double halfExtent(int r, int axis) {
        return geometry[r * DOUBLES + 12 + axis];
    }

    /** The radius of a sphere round region {@code r}'s centre holding it. */
    public double boundingRadius(int r) {
        return radius[r];
    }

    /** Region {@code r}'s density (zones: their own air's, replacing; else 0). */
    public float density(int r) {
        return media[r * FLOATS];
    }

    /** Region {@code r}'s falloff per metre of height (zones: 0). */
    public float falloff(int r) {
        return media[r * FLOATS + 1];
    }

    /** Region {@code r}'s blend distance, metres. */
    public float blendDistance(int r) {
        return media[r * FLOATS + 2];
    }

    /** Region {@code r}'s outdoor light kept inside (volumes: 1). */
    public float light(int r) {
        return media[r * FLOATS + 3];
    }

    /** Channel {@code channel} of region {@code r}'s albedo. */
    public float albedo(int r, int channel) {
        return media[r * FLOATS + 4 + channel];
    }

    /** The distance from ({@code x}, {@code y}, {@code z}) to region {@code r}'s bounding sphere (0 inside it). */
    public double distance(int r, double x, double y, double z) {
        int g = r * DOUBLES;
        double dx = geometry[g] - x;
        double dy = geometry[g + 1] - y;
        double dz = geometry[g + 2] - z;
        return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - radius[r]);
    }
}
