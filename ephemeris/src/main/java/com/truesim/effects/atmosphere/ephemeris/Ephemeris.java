package com.truesim.effects.atmosphere.ephemeris;

/**
 * The sky's bodies for an observer and a time: the sun and the moon (topocentric, horizontal, refracted as configured),
 * the moon's phase, their illuminance outside the atmosphere, and the rotation from equatorial coordinates of date to
 * the observer's horizon for the stars. {@link #update} allocates nothing; call it whenever the time or the observer
 * changes (every frame is fine: it costs a few microseconds).
 *
 * <pre>{@code
 * Ephemeris sky = new Ephemeris(EphemerisConfig.defaults());
 * sky.setObserver(new Observer(47.07, 15.44, 350));
 * sky.update(AstroTime.fromEpochMillis(gameClockMillis));
 * double sunUp = sky.sun().up();                    // the sun's direction: east(), north(), up()
 * double lit = sky.moonPhase().illuminatedFraction();
 * }</pre>
 */
public final class Ephemeris {

    private final CelestialBody sun = new CelestialBody();
    private final CelestialBody moon = new CelestialBody();
    private final MoonPhase moonPhase = new MoonPhase();
    private final double[] toHorizon = new double[9];
    private EphemerisConfig config;
    private Observer observer = Observer.GREENWICH;
    private double julianDay;
    private double julianDayTt;
    private double localSiderealTime;
    private double sunIlluminance;
    private double moonIlluminance;

    public Ephemeris(EphemerisConfig config) {
        setConfig(config);
    }

    public EphemerisConfig config() {
        return config;
    }

    public void setConfig(EphemerisConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config");
        }
        this.config = config;
    }

    public Observer observer() {
        return observer;
    }

    public void setObserver(Observer observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer");
        }
        this.observer = observer;
    }

    /** Computes everything for the UT (UTC) Julian day {@code jdUt}. */
    public void update(double jdUt) {
        julianDay = jdUt;
        julianDayTt = AstroTime.toTerrestrial(jdUt, config.deltaT(jdUt));
        double t = AstroTime.centuries(julianDayTt);
        Sun.geocentric(julianDayTt, sun);
        Moon.geocentric(julianDayTt, moon);
        moonPhase.compute(sun, moon);
        localSiderealTime = Angles.normalize(SiderealTime.greenwichApparent(jdUt, t) + observer.longitude());
        if (config.topocentric()) {
            Horizon.topocentric(sun, observer, config.planet(), localSiderealTime);
            Horizon.topocentric(moon, observer, config.planet(), localSiderealTime);
        }
        Horizon.horizontal(sun, localSiderealTime, observer.latitude(), config);
        Horizon.horizontal(moon, localSiderealTime, observer.latitude(), config);
        sunIlluminance = Photometry.illuminance(Photometry.sunMagnitude(sun.distanceKm()), config.zeroMagnitudeLux());
        moonIlluminance = Photometry.illuminance(
            Photometry.moonMagnitude(moonPhase.phaseAngle(), moon.distanceKm(), sun.distanceKm()),
            config.zeroMagnitudeLux());
        horizonMatrix(localSiderealTime, observer.latitude(), toHorizon);
    }

    /** The UT Julian day of the last update. */
    public double julianDay() {
        return julianDay;
    }

    /** The TT Julian day (JDE) of the last update. */
    public double julianDayTt() {
        return julianDayTt;
    }

    /** The local apparent sidereal time, degrees [0, 360). */
    public double localSiderealTime() {
        return localSiderealTime;
    }

    public CelestialBody sun() {
        return sun;
    }

    public CelestialBody moon() {
        return moon;
    }

    public MoonPhase moonPhase() {
        return moonPhase;
    }

    /** The sun's illuminance outside the atmosphere, lux, on a surface facing it. */
    public double sunIlluminance() {
        return sunIlluminance;
    }

    /** The moon's illuminance outside the atmosphere, lux, on a surface facing it (no earthshine). */
    public double moonIlluminance() {
        return moonIlluminance;
    }

    /**
     * The rotation (row-major 3×3) from equatorial unit vectors of date (x to the equinox, z to the north pole) to the
     * observer's (east, north, up): the stars' transform, without refraction (the renderer adds it near the horizon
     * with {@link Horizon#refraction}).
     */
    public double[] equatorialToHorizon() {
        return toHorizon;
    }

    /** Writes the rotation from equatorial of date to (east, north, up) for sidereal time and latitude (degrees). */
    public static void horizonMatrix(double lst, double latitude, double[] out) {
        double st = Angles.sin(lst);
        double ct = Angles.cos(lst);
        double sp = Angles.sin(latitude);
        double cp = Angles.cos(latitude);
        out[0] = -st;
        out[1] = ct;
        out[2] = 0;
        out[3] = -sp * ct;
        out[4] = -sp * st;
        out[5] = cp;
        out[6] = cp * ct;
        out[7] = cp * st;
        out[8] = sp;
    }
}
