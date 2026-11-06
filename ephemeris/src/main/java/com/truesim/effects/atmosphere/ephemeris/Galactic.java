package com.truesim.effects.atmosphere.ephemeris;

/**
 * Galactic coordinates (IAU 1958, as realised for ICRS/J2000 by the Hipparcos catalogue, ESA SP-1200 vol. 1 §1.5.3):
 * for placing the Milky Way. The rotation's rows are the galactic axes in J2000 equatorial coordinates.
 */
public final class Galactic {

    /** J2000 equatorial → galactic, row-major. */
    private static final double[] TO_GALACTIC = {
        -0.0548755604162154, -0.8734370902348850, -0.4838350155487132,
        0.4941094278755837, -0.4448296299600112, 0.7469822444972189,
        -0.8676661490190047, -0.1980763734312015, 0.4559837761750669,
    };

    private Galactic() {
    }

    /** Turns the J2000 equatorial unit vector at {@code in[offset..]} into galactic coordinates at {@code out[o..]}. */
    public static void fromEquatorial(double[] in, int offset, double[] out, int o) {
        double x = in[offset];
        double y = in[offset + 1];
        double z = in[offset + 2];
        double[] m = TO_GALACTIC;
        out[o] = m[0] * x + m[1] * y + m[2] * z;
        out[o + 1] = m[3] * x + m[4] * y + m[5] * z;
        out[o + 2] = m[6] * x + m[7] * y + m[8] * z;
    }

    /** Turns a galactic unit vector at {@code in[offset..]} into J2000 equatorial coordinates at {@code out[o..]}. */
    public static void toEquatorial(double[] in, int offset, double[] out, int o) {
        double x = in[offset];
        double y = in[offset + 1];
        double z = in[offset + 2];
        double[] m = TO_GALACTIC;
        out[o] = m[0] * x + m[3] * y + m[6] * z;
        out[o + 1] = m[1] * x + m[4] * y + m[7] * z;
        out[o + 2] = m[2] * x + m[5] * y + m[8] * z;
    }

    /** The galactic longitude l in degrees [0, 360) of a galactic unit vector. */
    public static double longitude(double[] v, int offset) {
        return Angles.normalize(Angles.atan2(v[offset + 1], v[offset]));
    }

    /** The galactic latitude b in degrees of a galactic unit vector. */
    public static double latitude(double[] v, int offset) {
        return Angles.asin(v[offset + 2]);
    }

    /** Writes the unit vector of right ascension and declination (degrees) at {@code out[o..]}. */
    public static void equatorialVector(double ra, double dec, double[] out, int o) {
        double c = Angles.cos(dec);
        out[o] = c * Angles.cos(ra);
        out[o + 1] = c * Angles.sin(ra);
        out[o + 2] = Angles.sin(dec);
    }
}
