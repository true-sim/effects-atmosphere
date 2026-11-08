package com.truesim.effects.atmosphere.fog;

import com.truesim.common.math.Vec3;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * A fog volume on a scene node: a box or ellipsoid of a medium of its own, placed, turned and scaled by the node's world
 * transform (its half extents times the node's scale), added to whatever fog is there (effects-atmosphere#4). Fog
 * lying in a hollow, steam over a pond, smoke in a hall. Saved with the scene; a hidden node's (on no layer) is off.
 *
 * <pre>{@code
 * scene.components().set(hollow, FogVolume.TYPE, FogVolume.box(new Vec3(20, 3, 20), 0.15f));
 * }</pre>
 *
 * @param shape         box or ellipsoid
 * @param halfExtents   half extents (radii) along the node's axes, metres, before its scale
 * @param density       extinction per metre at the volume's centre height
 * @param albedo        single-scattering albedo, 0 to 1 per channel (white fog 0.9; smoke lower)
 * @param falloff       per metre of height above the centre: the density times exp(-falloff * rise) (0: uniform)
 * @param blendDistance metres inside its surface over which it fades in (0: a hard edge)
 */
public record FogVolume(FogShape shape, Vec3 halfExtents, float density, Vec3 albedo, float falloff,
    float blendDistance) {

    /** The component type. */
    public static final ComponentType<FogVolume> TYPE = ComponentType.of("truesim.atmosphere.fog-volume",
        FogVolume.class);

    public FogVolume {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(halfExtents, "halfExtents");
        Objects.requireNonNull(albedo, "albedo");
        if (!(halfExtents.x() > 0 && halfExtents.y() > 0 && halfExtents.z() > 0)) {
            throw new IllegalArgumentException("A fog volume has a size: " + halfExtents);
        }
        if (!(density >= 0) || !(blendDistance >= 0) || !Float.isFinite(falloff)) {
            throw new IllegalArgumentException("Density and blend distance are not negative, the falloff finite: "
                + density + ", " + blendDistance + ", " + falloff);
        }
    }

    /** A uniform box of white fog ({@code density} per metre), hard-edged. */
    public static FogVolume box(Vec3 halfExtents, float density) {
        return new FogVolume(FogShape.BOX, halfExtents, density, new Vec3(0.9, 0.9, 0.9), 0, 0);
    }

    /** A uniform ellipsoid of white fog ({@code density} per metre), hard-edged. */
    public static FogVolume ellipsoid(Vec3 radii, float density) {
        return new FogVolume(FogShape.ELLIPSOID, radii, density, new Vec3(0.9, 0.9, 0.9), 0, 0);
    }

    /** This volume with {@code albedo}. */
    public FogVolume withAlbedo(Vec3 albedo) {
        return new FogVolume(shape, halfExtents, density, albedo, falloff, blendDistance);
    }

    /** This volume with {@code falloff}. */
    public FogVolume withFalloff(float falloff) {
        return new FogVolume(shape, halfExtents, density, albedo, falloff, blendDistance);
    }

    /** This volume with {@code blendDistance}. */
    public FogVolume withBlendDistance(float blendDistance) {
        return new FogVolume(shape, halfExtents, density, albedo, falloff, blendDistance);
    }
}
