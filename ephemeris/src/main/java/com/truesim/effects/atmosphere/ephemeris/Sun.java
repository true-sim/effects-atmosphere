package com.truesim.effects.atmosphere.ephemeris;

/**
 * The sun's geocentric apparent position, Meeus chapter 25 (the "low accuracy" method, within 0.01°): the geometric
 * longitude from the mean longitude and the equation of centre, corrected for aberration and nutation; the distance
 * from the orbit's eccentricity.
 */
public final class Sun {

    /** Kilometres per astronomical unit (IAU 2012). */
    public static final double AU_KM = 149_597_870.7;
    /** The sun's radius in kilometres (IAU 2015 nominal). */
    public static final double RADIUS_KM = 695_700.0;

    private Sun() {
    }

    /** The sun's mean anomaly, degrees, at {@code t} centuries (TT) from J2000. */
    static double meanAnomaly(double t) {
        return 357.52911 + t * (35999.05029 - 0.0001537 * t);
    }

    /** The eccentricity of the Earth's orbit. */
    static double eccentricity(double t) {
        return 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
    }

    /** The sun's true geometric longitude, degrees [0, 360) (Meeus 25.2–25.4). */
    public static double trueLongitude(double t) {
        double l0 = 280.46646 + t * (36000.76983 + 0.0003032 * t);
        return Angles.normalize(l0 + equationOfCentre(t));
    }

    private static double equationOfCentre(double t) {
        double m = meanAnomaly(t);
        return (1.914602 - t * (0.004817 + 0.000014 * t)) * Angles.sin(m)
            + (0.019993 - 0.000101 * t) * Angles.sin(2 * m) + 0.000289 * Angles.sin(3 * m);
    }

    /** The longitude of the Earth's perihelion, degrees (Meeus chapter 23, for annual aberration). */
    static double perihelion(double t) {
        return 102.93735 + t * (1.71946 + 0.00046 * t);
    }

    /** Fills {@code out}'s ecliptic and equatorial coordinates and distance for the TT Julian day {@code jde}. */
    public static void geocentric(double jde, CelestialBody out) {
        double t = AstroTime.centuries(jde);
        double e = eccentricity(t);
        double nu = meanAnomaly(t) + equationOfCentre(t);
        double r = 1.000001018 * (1 - e * e) / (1 + e * Angles.cos(nu));
        double omega = 125.04 - 1934.136 * t;
        double lambda = trueLongitude(t) - 0.00569 - 0.00478 * Angles.sin(omega);
        double epsilon = Nutation.meanObliquity(t) + 0.00256 * Angles.cos(omega);
        out.longitude = Angles.normalize(lambda);
        out.latitude = 0;
        out.distanceKm = r * AU_KM;
        out.rightAscension = Angles.normalize(
            Angles.atan2(Angles.cos(epsilon) * Angles.sin(lambda), Angles.cos(lambda)));
        out.declination = Angles.asin(Angles.sin(epsilon) * Angles.sin(lambda));
    }
}
