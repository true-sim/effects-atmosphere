package com.truesim.effects.atmosphere.ephemeris;

/**
 * Where the sky is seen from: geodetic latitude and longitude in degrees (north and east positive) and the altitude
 * above the ellipsoid in metres. Any altitude works, up to orbit; the topocentric moon and the refraction follow it.
 */
public record Observer(double latitude, double longitude, double altitude) {

    /** Greenwich at sea level. */
    public static final Observer GREENWICH = new Observer(51.4769, 0.0, 0.0);

    public Observer {
        if (!(latitude >= -90 && latitude <= 90) || !Double.isFinite(longitude) || !Double.isFinite(altitude)) {
            throw new IllegalArgumentException("Not an observer: " + latitude + ", " + longitude + ", " + altitude);
        }
    }
}
