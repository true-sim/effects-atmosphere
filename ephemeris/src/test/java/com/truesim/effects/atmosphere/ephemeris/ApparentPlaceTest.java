package com.truesim.effects.atmosphere.ephemeris;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Star places against Meeus' examples 21.b (mean place) and 23.a (apparent place) of θ Persei; galactic frame. */
class ApparentPlaceTest {

    // θ Persei, J2000.0: α 2h44m11.986s, δ +49°13'42.48", μα +0.03425 s/yr, μδ −0.0895"/yr.
    private static final double RA = (2 + 44 / 60.0 + 11.986 / 3600) * 15;
    private static final double DEC = 49 + 13 / 60.0 + 42.48 / 3600;
    private static final double PM_RA_COS_DEC = 0.03425 * 15 * 1000 * Angles.cos(DEC);
    private static final double PM_DEC = -89.5;
    private static final double JDE = 2462088.69; // 2028 November 13.19 TD

    @Test
    void meanPlaceOfExample21b() {
        ApparentPlace place = new ApparentPlace();
        place.prepare(JDE, false);
        double[] v = new double[3];
        place.apply(RA, DEC, PM_RA_COS_DEC, PM_DEC, v, 0);
        // α 2h46m11.331s, δ +49°20'54.54"
        assertThat(ApparentPlace.rightAscension(v, 0)).isCloseTo((2 + 46 / 60.0 + 11.331 / 3600) * 15,
            within(0.02 * Angles.ARCSEC * 15));
        assertThat(ApparentPlace.declination(v, 0)).isCloseTo(49 + 20 / 60.0 + 54.54 / 3600, within(0.05 * Angles.ARCSEC));
        assertThat(place.years()).isCloseTo(28.8670500, within(1e-6));
    }

    @Test
    void apparentPlaceOfExample23a() {
        ApparentPlace place = new ApparentPlace();
        place.prepare(JDE, true);
        double[] v = new double[3];
        place.apply(RA, DEC, PM_RA_COS_DEC, PM_DEC, v, 0);
        // α = 41.5599646°, δ = 49.3520685° (with the full nutation; ours is within 0.5").
        assertThat(ApparentPlace.rightAscension(v, 0)).isCloseTo(41.5599646, within(1.0 * Angles.ARCSEC));
        assertThat(ApparentPlace.declination(v, 0)).isCloseTo(49.3520685, within(1.0 * Angles.ARCSEC));
        assertThat(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void atJ2000WithoutMotionNothingMoves() {
        ApparentPlace place = new ApparentPlace();
        place.prepare(AstroTime.J2000, false);
        double[] v = new double[3];
        place.apply(123.4, -56.7, 0, 0, v, 0);
        assertThat(ApparentPlace.rightAscension(v, 0)).isCloseTo(123.4, within(1e-9));
        assertThat(ApparentPlace.declination(v, 0)).isCloseTo(-56.7, within(1e-9));
        place.apply(10, 90, 1000, 0, v, 0); // at the pole, μα means nothing
        assertThat(v[2]).isCloseTo(1, within(1e-12));
    }

    @Test
    void applyingAllocatesNothing() {
        ApparentPlace place = new ApparentPlace();
        double[] v = new double[3];
        assertAllocationFree(() -> {
            place.prepare(JDE, true);
            place.apply(RA, DEC, PM_RA_COS_DEC, PM_DEC, v, 0);
        });
    }

    @Test
    void galacticCentreAndNorthPole() {
        double[] eq = new double[3];
        double[] gal = new double[3];
        // Sgr A* region: the galactic centre at α 266.40499°, δ −28.93617° (J2000).
        Galactic.equatorialVector(266.40499, -28.93617, eq, 0);
        Galactic.fromEquatorial(eq, 0, gal, 0);
        assertThat(Galactic.latitude(gal, 0)).isCloseTo(0, within(1e-4));
        assertThat(Angles.signed(Galactic.longitude(gal, 0))).isCloseTo(0, within(1e-4));
        // The north galactic pole at α 192.85948°, δ +27.12825°.
        Galactic.equatorialVector(192.85948, 27.12825, eq, 0);
        Galactic.fromEquatorial(eq, 0, gal, 0);
        assertThat(Galactic.latitude(gal, 0)).isCloseTo(90, within(1e-4));
        double[] back = new double[3];
        Galactic.toEquatorial(gal, 0, back, 0);
        assertThat(back).containsExactly(eq, within(1e-12));
    }
}
