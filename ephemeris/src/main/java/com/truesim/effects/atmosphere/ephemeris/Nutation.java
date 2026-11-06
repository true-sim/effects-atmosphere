package com.truesim.effects.atmosphere.ephemeris;

/**
 * Nutation and the obliquity of the ecliptic, Meeus chapter 22: the mean obliquity by Meeus 22.2 (the IAU expression)
 * and the nutation's four main terms (accurate to 0.5" in longitude and 0.1" in obliquity, enough for anything drawn).
 */
public final class Nutation {

    private Nutation() {
    }

    private static double omega(double t) {
        return 125.04452 - 1934.136261 * t;
    }

    /** The nutation in longitude Δψ, in degrees, at {@code t} Julian centuries (TT) from J2000. */
    public static double longitude(double t) {
        double l = 280.4665 + 36000.7698 * t;
        double lm = 218.3165 + 481267.8813 * t;
        double om = omega(t);
        return (-17.20 * Angles.sin(om) - 1.32 * Angles.sin(2 * l) - 0.23 * Angles.sin(2 * lm)
            + 0.21 * Angles.sin(2 * om)) * Angles.ARCSEC;
    }

    /** The nutation in obliquity Δε, in degrees. */
    public static double obliquity(double t) {
        double l = 280.4665 + 36000.7698 * t;
        double lm = 218.3165 + 481267.8813 * t;
        double om = omega(t);
        return (9.20 * Angles.cos(om) + 0.57 * Angles.cos(2 * l) + 0.10 * Angles.cos(2 * lm)
            - 0.09 * Angles.cos(2 * om)) * Angles.ARCSEC;
    }

    /** The mean obliquity of the ecliptic ε₀ in degrees (Meeus 22.2). */
    public static double meanObliquity(double t) {
        return 23.0 + 26.0 / 60 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 3600;
    }

    /** The true obliquity ε = ε₀ + Δε in degrees. */
    public static double trueObliquity(double t) {
        return meanObliquity(t) + obliquity(t);
    }
}
