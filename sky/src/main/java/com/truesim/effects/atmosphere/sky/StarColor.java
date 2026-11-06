package com.truesim.effects.atmosphere.sky;

import com.truesim.common.math.Maths;

/**
 * Star colours: the effective temperature from the B−V index (Ballesteros 2012, a black body seen through the B and V
 * filters), then the black body's colour, integrating Planck's law against the CIE 1931 2° colour matching functions
 * (Wyman, Sloan and Shirley 2013's analytic fit) from 380 to 780 nm in 5 nm steps, converted to linear sRGB (D65).
 * The colour is normalised to luminance Y = 1, so a renderer multiplies it by the star's illuminance.
 */
public final class StarColor {

    /** Planck's second radiation constant c₂ = hc/k, in nm·K. */
    private static final double C2 = 1.438776877e7;
    /** The B−V used where a catalogue has none: the sun's (0.65). */
    public static final double DEFAULT_COLOR_INDEX = 0.65;

    private StarColor() {
    }

    /** The effective temperature in kelvin for a B−V index (Ballesteros 2012, eq. 14). */
    public static double temperature(double bv) {
        double b = Double.isNaN(bv) ? DEFAULT_COLOR_INDEX : Math.clamp(bv, -0.4, 2.5);
        return 4600.0 * (1.0 / (0.92 * b + 1.7) + 1.0 / (0.92 * b + 0.62));
    }

    /**
     * Writes the CIE 1931 XYZ of a black body of {@code kelvin}, normalised to Y = 1, into {@code out[o..o+2]}.
     */
    public static void blackBodyXyz(double kelvin, double[] out, int o) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (int nm = 380; nm <= 780; nm += 5) {
            double l = nm;
            double planck = 1.0 / (l * l * l * l * l * (Maths.exp(C2 / (l * kelvin)) - 1.0));
            x += planck * (1.056 * lobe(l, 599.8, 37.9, 31.0) + 0.362 * lobe(l, 442.0, 16.0, 26.7)
                - 0.065 * lobe(l, 501.1, 20.4, 26.2));
            y += planck * (0.821 * lobe(l, 568.8, 46.9, 40.5) + 0.286 * lobe(l, 530.9, 16.3, 31.1));
            z += planck * (1.217 * lobe(l, 437.0, 11.8, 36.0) + 0.681 * lobe(l, 459.0, 26.0, 13.8));
        }
        out[o] = x / y;
        out[o + 1] = 1.0;
        out[o + 2] = z / y;
    }

    /**
     * Writes the linear sRGB colour (Y = 1) of a star of B−V {@code bv} into {@code out[o..o+2]}; components outside
     * the sRGB gamut (the bluest stars' red) are clamped at 0.
     */
    public static void linearSrgb(double bv, float[] out, int o) {
        double[] xyz = new double[3];
        blackBodyXyz(temperature(bv), xyz, 0);
        double x = xyz[0];
        double y = xyz[1];
        double z = xyz[2];
        out[o] = (float) Math.max(0, 3.2404542 * x - 1.5371385 * y - 0.4985314 * z);
        out[o + 1] = (float) Math.max(0, -0.9692660 * x + 1.8760108 * y + 0.0415560 * z);
        out[o + 2] = (float) Math.max(0, 0.0556434 * x - 0.2040259 * y + 1.0572252 * z);
    }

    private static double lobe(double l, double mu, double below, double above) {
        double t = (l - mu) / (l < mu ? below : above);
        return Maths.exp(-0.5 * t * t);
    }
}
