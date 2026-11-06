package com.truesim.effects.atmosphere.ephemeris;

import com.truesim.effects.atmosphere.Quality;

/**
 * The tunables of the ephemerides.
 *
 * @param deltaTSeconds TT − UT in seconds; NaN takes Espenak and Meeus' model ({@link AstroTime#deltaT})
 * @param refraction whether apparent altitudes include atmospheric refraction (Saemundsson, Meeus 16.4)
 * @param pressureMillibar the air pressure at the observer, for refraction
 * @param temperatureCelsius the air temperature at the observer, for refraction
 * @param topocentric whether positions are seen from the observer (parallax: up to 1° for the moon) rather than from the
 *     planet's centre
 * @param planet the planet's shape, for parallax
 * @param zeroMagnitudeLux the illuminance of a star of visual magnitude 0 outside the atmosphere, in lux (Allen,
 *     <i>Astrophysical Quantities</i>: 2.54e-6 lux)
 */
public record EphemerisConfig(double deltaTSeconds, boolean refraction, double pressureMillibar,
    double temperatureCelsius, boolean topocentric, Planet planet, double zeroMagnitudeLux) {

    public EphemerisConfig {
        if (planet == null) {
            throw new IllegalArgumentException("planet");
        }
        if (!(pressureMillibar >= 0) || !(temperatureCelsius > -273.15) || !(zeroMagnitudeLux > 0)) {
            throw new IllegalArgumentException("Not an ephemeris configuration: " + pressureMillibar + " mbar, "
                + temperatureCelsius + " °C, " + zeroMagnitudeLux + " lux");
        }
    }

    /** ΔT from the model, refraction at standard air (1010 mbar, 10 °C), topocentric, Earth. */
    public static EphemerisConfig defaults() {
        return new EphemerisConfig(Double.NaN, true, 1010, 10, true, Planet.EARTH, 2.54e-6);
    }

    /** The defaults for any preset: the ephemerides cost microseconds, so every tier computes them in full. */
    public static EphemerisConfig forQuality(Quality quality) {
        return defaults();
    }

    /** ΔT for the UT Julian day {@code jdUt}: the configured value, or the model's. */
    public double deltaT(double jdUt) {
        return Double.isNaN(deltaTSeconds) ? AstroTime.deltaT(AstroTime.decimalYear(jdUt)) : deltaTSeconds;
    }

    public EphemerisConfig withDeltaT(double seconds) {
        return new EphemerisConfig(seconds, refraction, pressureMillibar, temperatureCelsius, topocentric, planet,
            zeroMagnitudeLux);
    }

    public EphemerisConfig withRefraction(boolean on, double millibar, double celsius) {
        return new EphemerisConfig(deltaTSeconds, on, millibar, celsius, topocentric, planet, zeroMagnitudeLux);
    }

    public EphemerisConfig withTopocentric(boolean on) {
        return new EphemerisConfig(deltaTSeconds, refraction, pressureMillibar, temperatureCelsius, on, planet,
            zeroMagnitudeLux);
    }
}
