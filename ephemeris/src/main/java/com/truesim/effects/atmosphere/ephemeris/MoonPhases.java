package com.truesim.effects.atmosphere.ephemeris;

/**
 * The dates of the principal phases: when the moon's apparent geocentric longitude exceeds the sun's by 0°, 90°, 180°
 * or 270°, found by iterating on {@link Moon} and {@link Sun} (secant steps from the mean synodic rate). Accurate to
 * about a minute, the precision of the truncated theories; for calendars, tests and "when is the next full moon".
 */
public final class MoonPhases {

    /** The mean synodic month in days. */
    public static final double SYNODIC_MONTH = 29.530588861;

    /** A principal phase and its longitude difference moon − sun. */
    public enum Phase {
        NEW(0),
        FIRST_QUARTER(90),
        FULL(180),
        LAST_QUARTER(270);

        private final double longitudeDifference;

        Phase(double longitudeDifference) {
            this.longitudeDifference = longitudeDifference;
        }

        /** The moon's longitude minus the sun's at this phase, degrees. */
        public double longitudeDifference() {
            return longitudeDifference;
        }
    }

    private final CelestialBody sun = new CelestialBody();
    private final CelestialBody moon = new CelestialBody();

    /** The TT Julian day of the first {@code phase} after the TT Julian day {@code jde}. */
    public double next(Phase phase, double jde) {
        double offset = offset(phase, jde);
        // Days until the phase at the mean rate; then refine. A phase just passed counts as the next lunation's.
        double t = jde + Angles.normalize(-offset) / 360.0 * SYNODIC_MONTH;
        for (int i = 0; i < 12; i++) {
            double step = -Angles.signed(offset(phase, t)) / 360.0 * SYNODIC_MONTH;
            t += step;
            if (Math.abs(step) < 1e-7) {
                break;
            }
        }
        return t;
    }

    /** The moon's longitude minus the sun's minus the phase's, degrees [0, 360). */
    private double offset(Phase phase, double jde) {
        Sun.geocentric(jde, sun);
        Moon.geocentric(jde, moon);
        return Angles.normalize(moon.longitude() - sun.longitude() - phase.longitudeDifference());
    }
}
