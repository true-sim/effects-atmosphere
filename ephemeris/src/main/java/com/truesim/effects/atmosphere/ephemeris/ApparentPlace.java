package com.truesim.effects.atmosphere.ephemeris;

import com.truesim.common.math.Maths;

/**
 * Catalogue positions (J2000.0) brought to a date: proper motion, then precession (Meeus 21.2–21.4, the IAU 1976
 * angles ζ, z, θ), then optionally nutation and annual aberration (Meeus chapter 23, the aberration as Meeus 23.3's
 * Earth velocity with the orbit's eccentricity). The rotations are folded into one matrix per date ({@link #prepare}),
 * so each star costs a matrix product, its proper motion and a vector addition; {@link #apply} allocates nothing.
 *
 * <p>Proper motion is linear in right ascension and declination, as catalogues give it and as Meeus applies it: right
 * for a few thousand years except within a degree or so of the poles. Annual parallax (below 1" for every star) and
 * light deflection are left out.
 */
public final class ApparentPlace {

    /** The constant of aberration κ in degrees (20.49552"). */
    public static final double ABERRATION = 20.49552 * Angles.ARCSEC;
    private static final double MAS_PER_DEGREE = 3_600_000.0;

    private final double[] m = new double[9];
    private final double[] p = new double[9];
    private final double[] n = new double[9];
    private final double[] r = new double[9];
    private final double[] tmp = new double[9];
    private double ax;
    private double ay;
    private double az;
    private double years;

    /**
     * Prepares the transformation from J2000.0 to the TT Julian day {@code jde}: mean place of date (precession only)
     * when {@code apparent} is false, apparent place (with nutation and aberration) when true.
     */
    public void prepare(double jde, boolean apparent) {
        double t = AstroTime.centuries(jde);
        years = (jde - AstroTime.J2000) / 365.25;
        double zeta = (t * (2306.2181 + t * (0.30188 + 0.017998 * t))) * Angles.ARCSEC;
        double z = (t * (2306.2181 + t * (1.09468 + 0.018203 * t))) * Angles.ARCSEC;
        double theta = (t * (2004.3109 - t * (0.42665 + 0.041833 * t))) * Angles.ARCSEC;
        rotZ(zeta, p);
        mulRotY(theta, p);
        mulRotZ(z, p);
        if (!apparent) {
            System.arraycopy(p, 0, m, 0, 9);
            ax = 0;
            ay = 0;
            az = 0;
            return;
        }
        double eps0 = Nutation.meanObliquity(t);
        double eps = eps0 + Nutation.obliquity(t);
        rotX(-eps0, n);
        mulRotZ(Nutation.longitude(t), n);
        mulRotX(eps, n);
        multiply(n, p, m);
        // The Earth's velocity over c in the ecliptic of date (Meeus 23.3), then in the equator of date.
        double sun = Sun.trueLongitude(t);
        double e = Sun.eccentricity(t);
        double pi = Sun.perihelion(t);
        double k = ABERRATION * Maths.DEGREES_TO_RADIANS;
        double vx = k * (Angles.sin(sun) - e * Angles.sin(pi));
        double vy = -k * (Angles.cos(sun) - e * Angles.cos(pi));
        ax = vx;
        ay = Angles.cos(eps) * vy;
        az = Angles.sin(eps) * vy;
    }

    /** Years since J2000.0 of the prepared date (Julian years). */
    public double years() {
        return years;
    }

    /**
     * Writes the unit direction of date (equatorial x towards the equinox, z towards the north pole) of a star at
     * J2000.0 right ascension {@code ra} and declination {@code dec} (degrees) with proper motion {@code pmRaCosDec}
     * (μα·cos δ) and {@code pmDec} in milliarcseconds per year into {@code out[offset..offset+2]}.
     */
    public void apply(double ra, double dec, double pmRaCosDec, double pmDec, double[] out, int offset) {
        double cosDec0 = Angles.cos(dec);
        double d = dec + pmDec * years / MAS_PER_DEGREE;
        double a = ra + (cosDec0 > 1e-9 ? pmRaCosDec / cosDec0 : 0.0) * years / MAS_PER_DEGREE;
        double cd = Angles.cos(d);
        double x = cd * Angles.cos(a);
        double y = cd * Angles.sin(a);
        double z = Angles.sin(d);
        double rx = m[0] * x + m[1] * y + m[2] * z;
        double ry = m[3] * x + m[4] * y + m[5] * z;
        double rz = m[6] * x + m[7] * y + m[8] * z;
        // Aberration: u' = u + v − (u·v) u, renormalised.
        double dot = rx * ax + ry * ay + rz * az;
        rx += ax - dot * rx;
        ry += ay - dot * ry;
        rz += az - dot * rz;
        double inv = 1.0 / Math.sqrt(rx * rx + ry * ry + rz * rz);
        out[offset] = rx * inv;
        out[offset + 1] = ry * inv;
        out[offset + 2] = rz * inv;
    }

    /** The right ascension in degrees [0, 360) of the unit direction at {@code v[offset..]}. */
    public static double rightAscension(double[] v, int offset) {
        return Angles.normalize(Angles.atan2(v[offset + 1], v[offset]));
    }

    /** The declination in degrees of the unit direction at {@code v[offset..]}. */
    public static double declination(double[] v, int offset) {
        return Angles.asin(v[offset + 2]);
    }

    // Active rotations (a vector turned by +angle), composed as out = R · out.

    private static void rotX(double angle, double[] out) {
        set(out, 1, 0, 0, 0, Angles.cos(angle), -Angles.sin(angle), 0, Angles.sin(angle), Angles.cos(angle));
    }

    private static void rotZ(double angle, double[] out) {
        set(out, Angles.cos(angle), -Angles.sin(angle), 0, Angles.sin(angle), Angles.cos(angle), 0, 0, 0, 1);
    }

    private void mulRotX(double angle, double[] out) {
        rotX(angle, r);
        premultiply(out);
    }

    private void mulRotZ(double angle, double[] out) {
        rotZ(angle, r);
        premultiply(out);
    }

    /** out = r · out. */
    private void premultiply(double[] out) {
        System.arraycopy(out, 0, tmp, 0, 9);
        multiply(r, tmp, out);
    }

    /** Meeus 21.4's middle step: x' = x cos θ − z sin θ, z' = x sin θ + z cos θ. */
    private void mulRotY(double theta, double[] out) {
        double c = Angles.cos(theta);
        double s = Angles.sin(theta);
        set(r, c, 0, -s, 0, 1, 0, s, 0, c);
        premultiply(out);
    }

    private static void set(double[] o, double a, double b, double c, double d, double e, double f, double g, double h,
        double i) {
        o[0] = a;
        o[1] = b;
        o[2] = c;
        o[3] = d;
        o[4] = e;
        o[5] = f;
        o[6] = g;
        o[7] = h;
        o[8] = i;
    }

    private static void multiply(double[] a, double[] b, double[] out) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 3 + col] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col];
            }
        }
    }
}
