package com.truesim.effects.atmosphere.ephemeris;

import com.truesim.common.math.Maths;

/**
 * Brightness of the sun, the moon and the stars outside the atmosphere, in visual magnitudes and in lux. The
 * atmosphere's extinction is the renderer's (its transmittance), not applied here.
 *
 * <p>Sources: the sun's apparent magnitude −26.74 at 1 AU (Allen's <i>Astrophysical Quantities</i>); the moon's
 * magnitude by phase angle V = −12.73 + 0.026|i| + 4·10⁻⁹ i⁴ at mean distance (Allen; also Krisciunas and Schaefer,
 * PASP 103, 1991) and the inverse square of the distances.
 */
public final class Photometry {

    /** The sun's visual magnitude at 1 AU. */
    public static final double SUN_MAGNITUDE = -26.74;
    /** The moon's mean distance in kilometres, at which its magnitude law holds. */
    public static final double MOON_MEAN_DISTANCE_KM = 384_400.0;

    private Photometry() {
    }

    /** The illuminance in lux of an object of visual magnitude {@code magnitude}, for a zero point in lux. */
    public static double illuminance(double magnitude, double zeroMagnitudeLux) {
        return zeroMagnitudeLux * Maths.pow(10.0, -0.4 * magnitude);
    }

    /** The visual magnitude of an illuminance in lux. */
    public static double magnitude(double lux, double zeroMagnitudeLux) {
        return -2.5 * Maths.log10(lux / zeroMagnitudeLux);
    }

    /** The sun's visual magnitude at {@code distanceKm}. */
    public static double sunMagnitude(double distanceKm) {
        return SUN_MAGNITUDE + 5 * Maths.log10(distanceKm / Sun.AU_KM);
    }

    /**
     * The moon's visual magnitude at phase angle {@code phaseAngle} (degrees) and the distances from the observer and
     * from the sun (km). It does not include the opposition surge's last few tenths of a degree or earthshine.
     */
    public static double moonMagnitude(double phaseAngle, double moonDistanceKm, double sunDistanceKm) {
        double i = Math.abs(phaseAngle);
        double i2 = i * i;
        return -12.73 + 0.026 * i + 4e-9 * i2 * i2
            + 5 * Maths.log10(moonDistanceKm / MOON_MEAN_DISTANCE_KM * sunDistanceKm / Sun.AU_KM);
    }
}
