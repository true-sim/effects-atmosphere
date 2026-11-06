package com.truesim.effects.atmosphere.ephemeris;

import com.truesim.common.math.Maths;

/** Angle helpers in degrees on common-math's deterministic functions: the same results on every machine. */
public final class Angles {

    /** Degrees in one arc second. */
    public static final double ARCSEC = 1.0 / 3600.0;

    private Angles() {
    }

    /** {@code degrees} reduced to [0, 360). */
    public static double normalize(double degrees) {
        double r = degrees - 360.0 * Math.floor(degrees / 360.0);
        return r >= 360.0 ? 0.0 : r;
    }

    /** {@code degrees} reduced to [-180, 180). */
    public static double signed(double degrees) {
        double r = normalize(degrees);
        return r >= 180.0 ? r - 360.0 : r;
    }

    public static double sin(double degrees) {
        return Maths.sin(degrees * Maths.DEGREES_TO_RADIANS);
    }

    public static double cos(double degrees) {
        return Maths.cos(degrees * Maths.DEGREES_TO_RADIANS);
    }

    public static double tan(double degrees) {
        return Maths.tan(degrees * Maths.DEGREES_TO_RADIANS);
    }

    /** The arc sine in degrees, its argument clamped to [-1, 1] against rounding. */
    public static double asin(double x) {
        return Maths.asin(Math.clamp(x, -1.0, 1.0)) * Maths.RADIANS_TO_DEGREES;
    }

    /** The arc cosine in degrees, its argument clamped to [-1, 1] against rounding. */
    public static double acos(double x) {
        return Maths.acos(Math.clamp(x, -1.0, 1.0)) * Maths.RADIANS_TO_DEGREES;
    }

    public static double atan(double x) {
        return Maths.atan(x) * Maths.RADIANS_TO_DEGREES;
    }

    /** The angle of (x, y) in degrees, in (-180, 180]. */
    public static double atan2(double y, double x) {
        return Maths.atan2(y, x) * Maths.RADIANS_TO_DEGREES;
    }
}
