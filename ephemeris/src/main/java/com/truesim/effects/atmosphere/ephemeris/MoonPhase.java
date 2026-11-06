package com.truesim.effects.atmosphere.ephemeris;

/**
 * The moon's phase and illumination from the sun's and the moon's positions, Meeus chapter 48 (without libration: the
 * lit fraction of the disc and the direction of its bright limb, which is what a renderer draws). Filled in place.
 */
public final class MoonPhase {

    private double elongation;
    private double phaseAngle;
    private double illuminatedFraction;
    private double brightLimb;
    private double age;

    /** The angle sun–earth–moon, degrees [0, 180] (Meeus 48.2). */
    public double elongation() {
        return elongation;
    }

    /** The angle sun–moon–earth, degrees [0, 180]: 0 at full moon (Meeus 48.3). */
    public double phaseAngle() {
        return phaseAngle;
    }

    /** The illuminated fraction of the disc, 0 (new) to 1 (full) (Meeus 48.1). */
    public double illuminatedFraction() {
        return illuminatedFraction;
    }

    /**
     * The position angle of the bright limb's midpoint, degrees [0, 360), measured from the north point of the disc
     * towards the east (Meeus 48.5); the renderer turns the lit side towards it.
     */
    public double brightLimb() {
        return brightLimb;
    }

    /**
     * Where in the lunation the moon is, [0, 1): 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter, from the
     * difference of the ecliptic longitudes.
     */
    public double age() {
        return age;
    }

    /** Whether the moon is waxing (the lit fraction growing). */
    public boolean waxing() {
        return age < 0.5;
    }

    /** Computes the phase from the sun's and the moon's equatorial coordinates, ecliptic longitudes and distances. */
    public void compute(CelestialBody sun, CelestialBody moon) {
        double a0 = sun.rightAscension();
        double d0 = sun.declination();
        double a = moon.rightAscension();
        double d = moon.declination();
        double cosPsi = Angles.sin(d0) * Angles.sin(d) + Angles.cos(d0) * Angles.cos(d) * Angles.cos(a0 - a);
        elongation = Angles.acos(cosPsi);
        double r = sun.distanceKm();
        double delta = moon.distanceKm();
        phaseAngle = Angles.atan2(r * Angles.sin(elongation), delta - r * cosPsi);
        illuminatedFraction = (1 + Angles.cos(phaseAngle)) / 2;
        brightLimb = Angles.normalize(Angles.atan2(Angles.cos(d0) * Angles.sin(a0 - a),
            Angles.sin(d0) * Angles.cos(d) - Angles.cos(d0) * Angles.sin(d) * Angles.cos(a0 - a)));
        age = Angles.normalize(moon.longitude() - sun.longitude()) / 360.0;
    }
}
