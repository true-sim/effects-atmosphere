package com.truesim.effects.atmosphere.ephemeris;

/**
 * The body the observer stands on: an ellipsoid of revolution. Earth by default; other planets are settings.
 *
 * @param equatorialRadius metres
 * @param flattening (a − b) / a; 0 for a sphere
 */
public record Planet(double equatorialRadius, double flattening) {

    /** Earth, IAU 1976 values as Meeus uses them (6,378,140 m, 1/298.257). */
    public static final Planet EARTH = new Planet(6_378_140.0, 1.0 / 298.257);

    public Planet {
        if (!(equatorialRadius > 0) || !(flattening >= 0 && flattening < 1)) {
            throw new IllegalArgumentException("Not a planet: radius " + equatorialRadius + ", flattening " + flattening);
        }
    }

    /** The polar to equatorial radius ratio b / a. */
    public double axisRatio() {
        return 1 - flattening;
    }
}
