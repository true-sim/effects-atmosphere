package com.truesim.effects.atmosphere.ephemeris;

/** Sidereal time, Meeus chapter 12: the Earth's rotation angle against the equinox. */
public final class SiderealTime {

    private SiderealTime() {
    }

    /** The mean sidereal time at Greenwich in degrees [0, 360), for the UT Julian day {@code jdUt} (Meeus 12.4). */
    public static double greenwichMean(double jdUt) {
        double t = AstroTime.centuries(jdUt);
        double theta = 280.46061837 + 360.98564736629 * (jdUt - AstroTime.J2000) + t * t * (0.000387933 - t / 38710000);
        return Angles.normalize(theta);
    }

    /**
     * The apparent sidereal time at Greenwich in degrees: the mean one corrected by the equation of the equinoxes,
     * Δψ cos ε, with {@code t} the Julian centuries in TT.
     */
    public static double greenwichApparent(double jdUt, double t) {
        return Angles.normalize(greenwichMean(jdUt) + Nutation.longitude(t) * Angles.cos(Nutation.trueObliquity(t)));
    }
}
