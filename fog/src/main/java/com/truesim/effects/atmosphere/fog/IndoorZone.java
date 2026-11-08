package com.truesim.effects.atmosphere.fog;

import com.truesim.common.math.Vec3;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * An indoor zone on a scene node: a box or ellipsoid (the node's world transform placing, turning and scaling it) where
 * the outdoor fog (the height fog of {@code atmosphere.fog}) does not reach, or is replaced by the zone's own air,
 * and where the outdoor light reaches the media only as much as {@link #light} says (effects-atmosphere#4). A room's
 * zone ends at its walls and windows: seen from outside, the air in front of the glass is fogged up to it and the room
 * behind it clear; seen from inside, the room is clear and the fog starts at the window. Fog volumes inside it still
 * add their media. Saved with the scene; a hidden node's (on no layer) is off.
 *
 * <pre>{@code
 * scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(4, 1.5, 3)));
 * }</pre>
 *
 * @param shape         box or ellipsoid
 * @param halfExtents   half extents (radii) along the node's axes, metres, before its scale
 * @param mode          whether it takes the outdoor fog out or replaces it
 * @param density       {@link Mode#REPLACE}: its own air's extinction per metre
 * @param albedo        {@link Mode#REPLACE}: its own air's single-scattering albedo
 * @param light         how much of the outdoor light (ambient and sun) reaches the media inside it, 0 to 1
 * @param blendDistance metres inside its surface over which it takes over (0: a hard edge, at the walls)
 */
public record IndoorZone(FogShape shape, Vec3 halfExtents, Mode mode, float density, Vec3 albedo, float light,
    float blendDistance) {

    /** The component type. */
    public static final ComponentType<IndoorZone> TYPE = ComponentType.of("truesim.atmosphere.indoor-zone",
        IndoorZone.class);

    /** What a zone does to the outdoor fog. */
    public enum Mode {
        /** Takes it out: the zone's air is clear but for fog volumes in it. */
        EXCLUDE,
        /** Replaces it by the zone's own air ({@link IndoorZone#density}, {@link IndoorZone#albedo}). */
        REPLACE
    }

    public IndoorZone {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(halfExtents, "halfExtents");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(albedo, "albedo");
        if (!(halfExtents.x() > 0 && halfExtents.y() > 0 && halfExtents.z() > 0)) {
            throw new IllegalArgumentException("An indoor zone has a size: " + halfExtents);
        }
        if (!(density >= 0) || !(blendDistance >= 0) || !(light >= 0 && light <= 1)) {
            throw new IllegalArgumentException("Density and blend distance are not negative, the light 0 to 1: "
                + density + ", " + blendDistance + ", " + light);
        }
    }

    /** A box-shaped room taking the outdoor fog out, its walls hard edges, a tenth of the outdoor light inside. */
    public static IndoorZone box(Vec3 halfExtents) {
        return new IndoorZone(FogShape.BOX, halfExtents, Mode.EXCLUDE, 0, new Vec3(0.9, 0.9, 0.9), 0.1f, 0);
    }

    /** This zone replacing the outdoor fog by air of {@code density} per metre and {@code albedo}. */
    public IndoorZone replacing(float density, Vec3 albedo) {
        return new IndoorZone(shape, halfExtents, Mode.REPLACE, density, albedo, light, blendDistance);
    }

    /** This zone with {@code light}. */
    public IndoorZone withLight(float light) {
        return new IndoorZone(shape, halfExtents, mode, density, albedo, light, blendDistance);
    }

    /** This zone with {@code blendDistance}. */
    public IndoorZone withBlendDistance(float blendDistance) {
        return new IndoorZone(shape, halfExtents, mode, density, albedo, light, blendDistance);
    }

    /** This zone as {@code shape}. */
    public IndoorZone withShape(FogShape shape) {
        return new IndoorZone(shape, halfExtents, mode, density, albedo, light, blendDistance);
    }
}
