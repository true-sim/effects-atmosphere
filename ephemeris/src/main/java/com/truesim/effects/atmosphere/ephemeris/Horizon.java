package com.truesim.effects.atmosphere.ephemeris;

/**
 * From equatorial coordinates to the observer's sky: the parallax that moves a near body when seen from the surface
 * (Meeus chapter 40), the horizontal coordinates (chapter 13) and atmospheric refraction (chapter 16).
 */
public final class Horizon {

    private Horizon() {
    }

    /**
     * Makes {@code body}'s equatorial coordinates topocentric for {@code observer} on {@code planet} (Meeus 40.2–40.3,
     * with the observer's altitude), at the local apparent sidereal time {@code lst} in degrees. The distance becomes
     * the observer's.
     */
    public static void topocentric(CelestialBody body, Observer observer, Planet planet, double lst) {
        double a = planet.equatorialRadius();
        double ratio = planet.axisRatio();
        double phi = observer.latitude();
        double u = Angles.atan(ratio * Angles.tan(phi));
        double rhoSin = ratio * Angles.sin(u) + observer.altitude() / a * Angles.sin(phi);
        double rhoCos = Angles.cos(u) + observer.altitude() / a * Angles.cos(phi);
        double sinPi = a / 1000.0 / body.distanceKm;
        double h = lst - body.rightAscension;
        double cosDec = Angles.cos(body.declination);
        double denominator = cosDec - rhoCos * sinPi * Angles.cos(h);
        double deltaRa = Angles.atan2(-rhoCos * sinPi * Angles.sin(h), denominator);
        double dec = Angles.atan2((Angles.sin(body.declination) - rhoSin * sinPi) * Angles.cos(deltaRa), denominator);
        // The distance to the observer: the body's position minus the observer's, in Earth radii.
        double x = Angles.cos(body.declination) * Angles.cos(h) - rhoCos * sinPi;
        double y = Angles.cos(body.declination) * Angles.sin(h);
        double z = Angles.sin(body.declination) - rhoSin * sinPi;
        body.distanceKm *= Math.sqrt(x * x + y * y + z * z);
        body.rightAscension = Angles.normalize(body.rightAscension + deltaRa);
        body.declination = dec;
    }

    /**
     * Fills {@code body}'s hour angle, azimuth (from north, eastwards) and altitude for the local apparent sidereal time
     * {@code lst} and the latitude {@code latitude}, degrees (Meeus 13.5–13.6), the apparent altitude with refraction
     * when {@code config} says so.
     */
    public static void horizontal(CelestialBody body, double lst, double latitude, EphemerisConfig config) {
        double h = Angles.normalize(lst - body.rightAscension);
        double sinPhi = Angles.sin(latitude);
        double cosPhi = Angles.cos(latitude);
        double sinDec = Angles.sin(body.declination);
        double cosDec = Angles.cos(body.declination);
        double cosH = Angles.cos(h);
        body.hourAngle = h;
        body.azimuth = Angles.normalize(
            Angles.atan2(Angles.sin(h) * cosDec, cosH * cosDec * sinPhi - sinDec * cosPhi) + 180.0);
        body.trueAltitude = Angles.asin(sinPhi * sinDec + cosPhi * cosDec * cosH);
        body.altitude = config.refraction()
            ? body.trueAltitude + refraction(body.trueAltitude, config.pressureMillibar(), config.temperatureCelsius())
            : body.trueAltitude;
    }

    /**
     * The refraction in degrees that lifts a body at true altitude {@code altitude} (degrees), Saemundsson's formula
     * (Meeus 16.4) scaled for pressure and temperature; it stays finite below the horizon, where it is held at its
     * value at −1°.
     */
    public static double refraction(double altitude, double pressureMillibar, double temperatureCelsius) {
        double h = Math.max(altitude, -1.0);
        double minutes = 1.02 / Angles.tan(h + 10.3 / (h + 5.11));
        return minutes / 60.0 * pressureMillibar / 1010.0 * 283.0 / (273.0 + temperatureCelsius);
    }
}
