package com.truesim.effects.atmosphere.ephemeris;

/**
 * Julian days and the time scales of the ephemerides. Positions are computed in Terrestrial Time (TT, Meeus' "JDE");
 * the Earth's rotation (sidereal time) in Universal Time (UT). They differ by ΔT ({@link #deltaT}), about 69 s in
 * 2025.
 *
 * <p>Sources: J. Meeus, <i>Astronomical Algorithms</i>, 2nd ed. (Willmann-Bell 1998), chapter 7 (Julian day);
 * F. Espenak and J. Meeus, <i>Five Millennium Canon of Solar Eclipses</i>, NASA/TP-2006-214141, section 2.6
 * (polynomial expressions for ΔT).
 */
public final class AstroTime {

    /** The Julian day of the epoch J2000.0 (2000 January 1.5 TT). */
    public static final double J2000 = 2451545.0;
    /** Days in a Julian century. */
    public static final double DAYS_PER_CENTURY = 36525.0;
    /** The Julian day of the Unix epoch, 1970-01-01T00:00Z. */
    public static final double UNIX_EPOCH = 2440587.5;
    /** Milliseconds per day. */
    public static final double MILLIS_PER_DAY = 86_400_000.0;

    private AstroTime() {
    }

    /**
     * The Julian day of a date and time in the Gregorian calendar (Meeus 7.1), in the time scale the arguments are in.
     * {@code day} may carry a fraction.
     */
    public static double julianDay(int year, int month, double day) {
        int y = year;
        int m = month;
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        int a = Math.floorDiv(y, 100);
        int b = 2 - a + Math.floorDiv(a, 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + day + b - 1524.5;
    }

    /** The Julian day of a date and time of day in the Gregorian calendar. */
    public static double julianDay(int year, int month, int day, int hour, int minute, double second) {
        return julianDay(year, month, day + (hour + (minute + second / 60.0) / 60.0) / 24.0);
    }

    /** The Julian day (UTC, taken as UT) of milliseconds since the Unix epoch, e.g. {@code Instant.toEpochMilli()}. */
    public static double fromEpochMillis(long epochMillis) {
        return UNIX_EPOCH + epochMillis / MILLIS_PER_DAY;
    }

    /** Julian centuries since J2000.0 of the Julian day {@code jd}. */
    public static double centuries(double jd) {
        return (jd - J2000) / DAYS_PER_CENTURY;
    }

    /** The decimal year of the Julian day {@code jd}, close enough for ΔT. */
    public static double decimalYear(double jd) {
        return 2000.0 + (jd - J2000) / 365.25;
    }

    /**
     * ΔT = TT − UT in seconds for a decimal year: Espenak and Meeus' polynomials for 1900–2150, their long-term parabola
     * outside. Within about a second from 1900 to 2005 (measured values); a prediction afterwards (it gives ≈ 74 s for
     * 2024 against a measured ≈ 69 s), which is why {@link EphemerisConfig#deltaTSeconds} can set it.
     */
    public static double deltaT(double year) {
        double y = year;
        if (y >= 2150 || y < 1900) {
            double u = (y - 1820) / 100;
            return -20 + 32 * u * u;
        }
        if (y >= 2050) {
            double u = (y - 1820) / 100;
            return -20 + 32 * u * u - 0.5628 * (2150 - y);
        }
        if (y >= 2005) {
            double t = y - 2000;
            return 62.92 + 0.32217 * t + 0.005589 * t * t;
        }
        if (y >= 1986) {
            double t = y - 2000;
            return 63.86 + t * (0.3345 + t * (-0.060374 + t * (0.0017275 + t * (0.000651814 + t * 0.00002373599))));
        }
        if (y >= 1961) {
            double t = y - 1975;
            return 45.45 + 1.067 * t - t * t / 260 - t * t * t / 718;
        }
        if (y >= 1941) {
            double t = y - 1950;
            return 29.07 + 0.407 * t - t * t / 233 + t * t * t / 2547;
        }
        if (y >= 1920) {
            double t = y - 1920;
            return 21.20 + t * (0.84493 + t * (-0.076100 + t * 0.0020936));
        }
        double t = y - 1900;
        return -2.79 + t * (1.494119 + t * (-0.0598939 + t * (0.0061966 - t * 0.000197)));
    }

    /** The TT Julian day (JDE) of the UT Julian day {@code jdUt}, with ΔT in seconds. */
    public static double toTerrestrial(double jdUt, double deltaTSeconds) {
        return jdUt + deltaTSeconds / 86400.0;
    }
}
