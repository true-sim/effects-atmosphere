package com.truesim.effects.atmosphere.ephemeris;

/**
 * Where a body is, filled in place by {@link Sun}, {@link Moon} and {@link Ephemeris} so updates allocate nothing.
 * Angles are degrees. {@link Sun#geocentric} and {@link Moon#geocentric} fill the ecliptic and equatorial coordinates
 * as seen from the planet's centre; {@link Ephemeris#update} then makes the equatorial ones topocentric (if configured)
 * and fills the horizontal ones (apparent, with refraction if configured).
 */
public final class CelestialBody {

    double longitude;
    double latitude;
    double distanceKm;
    double rightAscension;
    double declination;
    double hourAngle;
    double azimuth;
    double altitude;
    double trueAltitude;

    /** Apparent ecliptic longitude of date, degrees [0, 360). */
    public double longitude() {
        return longitude;
    }

    /** Ecliptic latitude, degrees. */
    public double latitude() {
        return latitude;
    }

    /** Distance in kilometres: from the planet's centre, or from the observer once topocentric. */
    public double distanceKm() {
        return distanceKm;
    }

    /** Right ascension of date, degrees [0, 360). */
    public double rightAscension() {
        return rightAscension;
    }

    /** Declination of date, degrees. */
    public double declination() {
        return declination;
    }

    /** Local hour angle, degrees [0, 360); set by {@link Ephemeris}. */
    public double hourAngle() {
        return hourAngle;
    }

    /** Azimuth from north towards east, degrees [0, 360); set by {@link Ephemeris}. */
    public double azimuth() {
        return azimuth;
    }

    /** Apparent altitude above the horizon, degrees: with refraction if configured; set by {@link Ephemeris}. */
    public double altitude() {
        return altitude;
    }

    /** Altitude without refraction, degrees. */
    public double trueAltitude() {
        return trueAltitude;
    }

    /** The unit direction towards the body in the observer's frame (east, north, up): the east component. */
    public double east() {
        return Angles.sin(azimuth) * Angles.cos(altitude);
    }

    /** The unit direction's north component. */
    public double north() {
        return Angles.cos(azimuth) * Angles.cos(altitude);
    }

    /** The unit direction's up (zenith) component. */
    public double up() {
        return Angles.sin(altitude);
    }

    /** The angular radius of a body of {@code radiusKm} at this distance, degrees. */
    public double angularRadius(double radiusKm) {
        return Angles.asin(radiusKm / distanceKm);
    }

    /** Sets the equatorial coordinates of date (degrees) and the distance, e.g. for a body computed elsewhere. */
    public void setEquatorial(double ra, double dec, double km) {
        rightAscension = Angles.normalize(ra);
        declination = dec;
        distanceKm = km;
    }
}
