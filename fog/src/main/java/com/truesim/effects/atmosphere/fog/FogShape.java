package com.truesim.effects.atmosphere.fog;

/** The shape of a fog region: its node's transform places it, its half extents (radii) size it along its axes. */
public enum FogShape {
    /** A box of half extents along its axes. */
    BOX,
    /** An ellipsoid of radii along its axes. */
    ELLIPSOID
}
