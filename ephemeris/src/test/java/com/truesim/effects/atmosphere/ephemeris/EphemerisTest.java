package com.truesim.effects.atmosphere.ephemeris;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.atmosphere.Quality;
import org.junit.jupiter.api.Test;

/** The ephemerides against the published worked examples of Meeus and NREL's SPA, and USNO phase times. */
class EphemerisTest {

    private static double hms(double h, double m, double s) {
        return (h + m / 60 + s / 3600) * 15;
    }

    private static double dms(double d, double m, double s) {
        return Math.copySign(Math.abs(d) + m / 60 + s / 3600, d);
    }

    @Test
    void julianDaysOfMeeusChapter7() {
        assertThat(AstroTime.julianDay(2000, 1, 1.5)).isEqualTo(2451545.0);
        assertThat(AstroTime.julianDay(1957, 10, 4.81)).isCloseTo(2436116.31, within(1e-9));
        assertThat(AstroTime.julianDay(1987, 6, 19.5)).isEqualTo(2446966.0);
        assertThat(AstroTime.julianDay(1600, 12, 31.0)).isEqualTo(2305812.5);
        assertThat(AstroTime.julianDay(1988, 1, 27, 0, 0, 0)).isEqualTo(2447187.5);
        assertThat(AstroTime.fromEpochMillis(0)).isEqualTo(2440587.5);
        assertThat(AstroTime.fromEpochMillis(946_728_000_000L)).isEqualTo(AstroTime.J2000);
        assertThat(AstroTime.decimalYear(AstroTime.J2000)).isEqualTo(2000.0);
        assertThat(AstroTime.toTerrestrial(2451545.0, 86400)).isEqualTo(2451546.0);
    }

    @Test
    void deltaTFollowsTheMeasuredValuesWithinASecond() {
        // Measured ΔT (IERS / Espenak & Meeus table 2.1): 1905 3.9, 1930 24.0, 1950 29.1, 1970 40.2, 1990 56.9, 2000 63.8.
        assertThat(AstroTime.deltaT(1905)).isCloseTo(3.9, within(1.0));
        assertThat(AstroTime.deltaT(1930)).isCloseTo(24.0, within(1.0));
        assertThat(AstroTime.deltaT(1950)).isCloseTo(29.1, within(1.0));
        assertThat(AstroTime.deltaT(1970)).isCloseTo(40.2, within(1.0));
        assertThat(AstroTime.deltaT(1990)).isCloseTo(56.9, within(1.0));
        assertThat(AstroTime.deltaT(2000)).isCloseTo(63.8, within(1.0));
        assertThat(AstroTime.deltaT(2020)).isCloseTo(71.6, within(4.0)); // a prediction: measured 69.4
        assertThat(AstroTime.deltaT(2100)).isCloseTo(202.7, within(1.0)); // the polynomial's own value
        assertThat(AstroTime.deltaT(1700)).isCloseTo(-20 + 32 * 1.44, within(1e-9)); // the long-term parabola
        assertThat(AstroTime.deltaT(2200)).isCloseTo(-20 + 32 * 3.8 * 3.8, within(1e-9));
    }

    @Test
    void siderealTimeOfMeeusExamples12() {
        double jd = AstroTime.julianDay(1987, 4, 10.0);
        assertThat(SiderealTime.greenwichMean(jd)).isCloseTo(hms(13, 10, 46.3668), within(1e-6));
        double later = AstroTime.julianDay(1987, 4, 10, 19, 21, 0);
        assertThat(SiderealTime.greenwichMean(later)).isCloseTo(128.7378734, within(1e-6));
        // Apparent: 13h10m46.1351s (with the full nutation; ours is within 0.5" of it, 0.03 s of time).
        assertThat(SiderealTime.greenwichApparent(jd, AstroTime.centuries(jd))).isCloseTo(hms(13, 10, 46.1351),
            within(0.5 * Angles.ARCSEC));
    }

    @Test
    void nutationOfMeeusExample22a() {
        double t = AstroTime.centuries(2446895.5);
        assertThat(Nutation.longitude(t) / Angles.ARCSEC).isCloseTo(-3.788, within(0.5));
        assertThat(Nutation.obliquity(t) / Angles.ARCSEC).isCloseTo(9.443, within(0.1));
        assertThat(Nutation.meanObliquity(t)).isCloseTo(dms(23, 26, 27.407), within(0.001 * Angles.ARCSEC));
        assertThat(Nutation.trueObliquity(t)).isCloseTo(dms(23, 26, 36.850), within(0.1 * Angles.ARCSEC));
    }

    @Test
    void sunOfMeeusExample25a() {
        CelestialBody sun = new CelestialBody();
        Sun.geocentric(2448908.5, sun);
        assertThat(sun.longitude()).isCloseTo(199.90894, within(1e-4));
        assertThat(sun.distanceKm() / Sun.AU_KM).isCloseTo(0.99766, within(1e-5));
        assertThat(sun.rightAscension()).isCloseTo(hms(13, 13, 31.4), within(1e-3));
        assertThat(sun.declination()).isCloseTo(dms(-7, 47, 6), within(1e-3));
        assertThat(sun.latitude()).isZero();
        assertThat(sun.angularRadius(Sun.RADIUS_KM)).isCloseTo(0.2672, within(1e-3)); // 16'02" on 13 October
    }

    @Test
    void moonOfMeeusExample47a() {
        CelestialBody moon = new CelestialBody();
        Moon.geocentric(2448724.5, moon);
        // λ = 133.162655° geometric + Δψ 0.004610° (full nutation; ours within 0.5").
        assertThat(moon.longitude()).isCloseTo(133.167265, within(2e-4));
        assertThat(moon.latitude()).isCloseTo(-3.229126, within(1e-6));
        assertThat(moon.distanceKm()).isCloseTo(368409.7, within(0.1));
        assertThat(moon.rightAscension()).isCloseTo(134.688470, within(3e-4));
        assertThat(moon.declination()).isCloseTo(13.768368, within(3e-4));
    }

    @Test
    void topocentricSunOfTheNrelSpaExample() {
        // Reda and Andreas, Solar Position Algorithm for Solar Radiation Applications, NREL/TP-560-34302 (2008), table
        // A5.1: 2003-10-17 12:30:30 at UTC−7, 39.742476° N 105.1786° W, 1830.14 m, ΔT 67 s, 820 mbar, 11 °C:
        // topocentric zenith 50.11162°, azimuth 194.34024°. Meeus' low-accuracy sun is good to 0.01°.
        Ephemeris sky = new Ephemeris(EphemerisConfig.defaults().withDeltaT(67).withRefraction(true, 820, 11));
        sky.setObserver(new Observer(39.742476, -105.1786, 1830.14));
        sky.update(AstroTime.julianDay(2003, 10, 17, 19, 30, 30));
        assertThat(90 - sky.sun().altitude()).isCloseTo(50.11162, within(0.01));
        assertThat(sky.sun().azimuth()).isCloseTo(194.34024, within(0.01));
        assertThat(sky.julianDayTt() - sky.julianDay()).isCloseTo(67 / 86400.0, within(1e-9));
        assertThat(sky.observer().latitude()).isEqualTo(39.742476);
        assertThat(sky.config().refraction()).isTrue();
    }

    @Test
    void moonPhaseOfMeeusExample48a() {
        CelestialBody sun = new CelestialBody();
        CelestialBody moon = new CelestialBody();
        Sun.geocentric(2448724.5, sun);
        Moon.geocentric(2448724.5, moon);
        MoonPhase phase = new MoonPhase();
        phase.compute(sun, moon);
        assertThat(phase.phaseAngle()).isCloseTo(69.0756, within(0.01));
        assertThat(phase.illuminatedFraction()).isCloseTo(0.6786, within(1e-4));
        assertThat(phase.brightLimb()).isCloseTo(285.0, within(0.1));
        assertThat(phase.elongation()).isCloseTo(110.79, within(0.02));
        assertThat(phase.waxing()).isTrue();
        assertThat(phase.age()).isBetween(0.25, 0.5);
    }

    @Test
    void phaseDatesOfMeeusExample49aAndUsno2024() {
        MoonPhases phases = new MoonPhases();
        // Meeus 49.a: new moon 1977 Feb 18 3h37m42s TD = JDE 2443192.65118 (one minute = 0.0007 d).
        assertThat(phases.next(MoonPhases.Phase.NEW, AstroTime.julianDay(1977, 2, 10.0)))
            .isCloseTo(2443192.65118, within(0.0007));
        // USNO, Phases of the Moon 2024 (UT): new Jan 11 11:57, first quarter Jan 18 03:53, full Jan 25 17:54, last
        // quarter Feb 2 23:18. ΔT 69.2 s (IERS).
        double start = AstroTime.julianDay(2024, 1, 5.0);
        double deltaT = 69.2 / 86400;
        double twoMinutes = 2.0 / 1440;
        assertThat(phases.next(MoonPhases.Phase.NEW, start) - deltaT)
            .isCloseTo(AstroTime.julianDay(2024, 1, 11, 11, 57, 0), within(twoMinutes));
        assertThat(phases.next(MoonPhases.Phase.FIRST_QUARTER, start) - deltaT)
            .isCloseTo(AstroTime.julianDay(2024, 1, 18, 3, 53, 0), within(twoMinutes));
        assertThat(phases.next(MoonPhases.Phase.FULL, start) - deltaT)
            .isCloseTo(AstroTime.julianDay(2024, 1, 25, 17, 54, 0), within(twoMinutes));
        assertThat(phases.next(MoonPhases.Phase.LAST_QUARTER, start) - deltaT)
            .isCloseTo(AstroTime.julianDay(2024, 2, 2, 23, 18, 0), within(twoMinutes));
        assertThat(MoonPhases.Phase.FULL.longitudeDifference()).isEqualTo(180);
    }

    @Test
    void fullMoonIsFullAndNewMoonDark() {
        Ephemeris sky = new Ephemeris(EphemerisConfig.defaults().withDeltaT(69.2));
        sky.update(AstroTime.julianDay(2024, 1, 25, 17, 54, 0));
        assertThat(sky.moonPhase().illuminatedFraction()).isGreaterThan(0.99);
        assertThat(sky.moonPhase().age()).isCloseTo(0.5, within(0.002));
        assertThat(sky.moonIlluminance()).isBetween(0.2, 0.4);
        sky.update(AstroTime.julianDay(2024, 1, 11, 11, 57, 0));
        assertThat(sky.moonPhase().illuminatedFraction()).isLessThan(0.01);
        sky.update(AstroTime.julianDay(2024, 2, 2, 23, 18, 0));
        assertThat(sky.moonPhase().waxing()).isFalse();
        assertThat(sky.moonPhase().illuminatedFraction()).isCloseTo(0.5, within(0.01));
        sky.moon().setEquatorial(-10, 5, 1000);
        assertThat(sky.moon().rightAscension()).isEqualTo(350);
        assertThat(sky.moon().declination()).isEqualTo(5);
        assertThat(sky.moon().distanceKm()).isEqualTo(1000);
        assertThat(sky.sunIlluminance()).isBetween(1.2e5, 1.35e5); // January: near perihelion
    }

    @Test
    void photometryRoundTripsAndFollowsAllen() {
        double zero = EphemerisConfig.defaults().zeroMagnitudeLux();
        assertThat(Photometry.magnitude(Photometry.illuminance(-1.46, zero), zero)).isCloseTo(-1.46, within(1e-12));
        assertThat(Photometry.sunMagnitude(Sun.AU_KM)).isEqualTo(-26.74);
        assertThat(Photometry.moonMagnitude(0, Photometry.MOON_MEAN_DISTANCE_KM, Sun.AU_KM)).isCloseTo(-12.73,
            within(1e-12));
        assertThat(Photometry.moonMagnitude(90, Photometry.MOON_MEAN_DISTANCE_KM, Sun.AU_KM)).isCloseTo(-10.13,
            within(0.01));
    }

    @Test
    void refractionAtTheHorizonIsAboutHalfADegree() {
        assertThat(Horizon.refraction(0, 1010, 10)).isCloseTo(0.483, within(0.002));
        assertThat(Horizon.refraction(90, 1010, 10)).isCloseTo(0, within(1e-4));
        assertThat(Horizon.refraction(-10, 1010, 10)).isEqualTo(Horizon.refraction(-1, 1010, 10));
        assertThat(Horizon.refraction(0, 0, 10)).isZero(); // no air, no refraction
    }

    @Test
    void theHorizonMatrixAgreesWithTheHorizontalCoordinates() {
        Ephemeris sky = new Ephemeris(EphemerisConfig.forQuality(Quality.LOW).withRefraction(false, 1010, 10));
        sky.setObserver(new Observer(-33.9, 151.2, 50));
        sky.update(AstroTime.julianDay(2025, 6, 21, 3, 0, 0));
        CelestialBody moon = sky.moon();
        double[] v = new double[3];
        Galactic.equatorialVector(moon.rightAscension(), moon.declination(), v, 0);
        double[] m = sky.equatorialToHorizon();
        assertThat(m[0] * v[0] + m[1] * v[1] + m[2] * v[2]).isCloseTo(moon.east(), within(1e-9));
        assertThat(m[3] * v[0] + m[4] * v[1] + m[5] * v[2]).isCloseTo(moon.north(), within(1e-9));
        assertThat(m[6] * v[0] + m[7] * v[1] + m[8] * v[2]).isCloseTo(moon.up(), within(1e-9));
        assertThat(moon.trueAltitude()).isEqualTo(moon.altitude());
        assertThat(moon.hourAngle()).isBetween(0.0, 360.0);
        assertThat(sky.localSiderealTime()).isBetween(0.0, 360.0);
    }

    @Test
    void parallaxMovesTheMoonByUpToADegree() {
        Ephemeris geo = new Ephemeris(EphemerisConfig.defaults().withTopocentric(false).withDeltaT(69));
        Ephemeris topo = new Ephemeris(EphemerisConfig.defaults().withDeltaT(69));
        double jd = AstroTime.julianDay(2025, 3, 1, 18, 0, 0);
        geo.update(jd);
        topo.update(jd);
        double shift = geo.moon().trueAltitude() - topo.moon().trueAltitude();
        // At altitude h the shift is ≈ π cos h, with the horizontal parallax sin π = R / Δ (0.9°–1.0°).
        double parallax = Angles.asin(6378.14 / geo.moon().distanceKm());
        assertThat(shift).isCloseTo(parallax * Angles.cos(geo.moon().trueAltitude()), within(0.02));
        assertThat(topo.moon().distanceKm()).isLessThan(geo.moon().distanceKm() + 6400);
    }

    @Test
    void updatingAllocatesNothing() {
        Ephemeris sky = new Ephemeris(EphemerisConfig.defaults());
        sky.setObserver(new Observer(47.07, 15.44, 350));
        double[] jd = {AstroTime.julianDay(2026, 11, 6.5)};
        assertAllocationFree(() -> {
            sky.update(jd[0]);
            jd[0] += 1.0 / 86400;
        });
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Observer(91, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Observer(0, Double.NaN, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Planet(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Planet(1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EphemerisConfig(0, true, -1, 10, true, Planet.EARTH, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EphemerisConfig(0, true, 1, 10, true, null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Ephemeris(null));
        Ephemeris sky = new Ephemeris(EphemerisConfig.defaults());
        assertThatIllegalArgumentException().isThrownBy(() -> sky.setObserver(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Planet(1, -0.1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Observer(0, 0, Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> new Observer(-91, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new EphemerisConfig(0, true, 1, -300, true, Planet.EARTH, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EphemerisConfig(0, true, 1, 10, true, Planet.EARTH, 0));
        assertThat(Planet.EARTH.axisRatio()).isCloseTo(0.99664719, within(1e-8));
    }

    @Test
    void anglesNormalise() {
        assertThat(Angles.normalize(-30)).isEqualTo(330);
        assertThat(Angles.normalize(720)).isZero();
        assertThat(Angles.normalize(-1e-300)).isZero();
        assertThat(Angles.signed(270)).isEqualTo(-90);
        assertThat(Angles.signed(90)).isEqualTo(90);
        assertThat(Angles.acos(1.0000001)).isZero();
        assertThat(Angles.asin(-1.0000001)).isEqualTo(-90);
    }
}
