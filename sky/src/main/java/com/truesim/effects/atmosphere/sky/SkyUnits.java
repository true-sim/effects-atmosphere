package com.truesim.effects.atmosphere.sky;

import com.truesim.common.math.Maths;

/** Conversions between astronomical surface brightness and photometric units. */
public final class SkyUnits {

    /** cd/m² of a surface of 0 V mag/arcsec² (10.8·10⁴, the conversion commonly used in night-sky brightness work; 22 mag/arcsec² gives 1.7·10⁻⁴ cd/m²). */
    public static final double LUMINANCE_AT_ZERO_MAGNITUDE = 1.08e5;

    private SkyUnits() {
    }

    /** The luminance in cd/m² of a surface brightness in V mag/arcsec². */
    public static double luminance(double magnitudePerSquareArcsecond) {
        return LUMINANCE_AT_ZERO_MAGNITUDE * Maths.pow(10.0, -0.4 * magnitudePerSquareArcsecond);
    }

    /** The surface brightness in V mag/arcsec² of a luminance in cd/m². */
    public static double magnitude(double luminance) {
        return -2.5 * Maths.log10(luminance / LUMINANCE_AT_ZERO_MAGNITUDE);
    }
}
