package com.truesim.effects.atmosphere.sky;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.common.math.random.Rng;
import com.truesim.effects.atmosphere.ephemeris.ApparentPlace;
import com.truesim.effects.atmosphere.ephemeris.Galactic;
import com.truesim.scene.assets.Generator;

/**
 * The cheap star tier: a deterministic random catalogue, no data file. Magnitudes follow the observed star counts
 * (log₁₀ N(&lt;m) grows by about 0.48 per magnitude near the naked-eye limit, so 15,000 stars to magnitude 7), B−V a
 * clipped normal distribution around the sun's colour, and positions crowd towards the galactic plane by
 * {@link Params#planeConcentration}. Made as a generated asset ({@link SkyAssets#procedural}) or directly.
 */
public final class ProceduralStars {

    /**
     * The recipe.
     *
     * @param count how many stars
     * @param limitingMagnitude the faintest magnitude
     * @param brightestMagnitude the brightest
     * @param planeConcentration 0 for uniform positions; larger crowds stars towards the galactic plane
     */
    public record Params(int count, double limitingMagnitude, double brightestMagnitude, double planeConcentration) {

        public Params {
            if (count < 0 || !(brightestMagnitude < limitingMagnitude) || !(planeConcentration >= 0)) {
                throw new IllegalArgumentException("Not a procedural star recipe: " + count + " stars, magnitudes "
                    + brightestMagnitude + " to " + limitingMagnitude + ", concentration " + planeConcentration);
            }
        }

        /** About the real sky's numbers: 9,000 stars to magnitude 6.5, mildly concentrated to the Milky Way. */
        public static Params defaults() {
            return new Params(9000, 6.5, -1.5, 1.0);
        }
    }

    /** The asset generator: equal recipes and seeds make one catalogue. */
    public static final Generator<Params, StarCatalogue> GENERATOR = ProceduralStars::generate;

    /** The slope of log₁₀ N(&lt;m) per magnitude. */
    private static final double COUNT_SLOPE = 0.48;

    private ProceduralStars() {
    }

    /** Generates the catalogue of {@code params} from {@code seed}: the same on every machine. */
    @AllowAllocation("generating an asset")
    public static StarCatalogue generate(Params params, long seed) {
        Rng rng = Rng.seeded(seed);
        int n = params.count();
        double[] ra = new double[n];
        double[] dec = new double[n];
        float[] mag = new float[n];
        float[] bv = new float[n];
        double[] gal = new double[3];
        double[] eq = new double[3];
        // N(<m) ∝ 10^(k m): m = mlim + log10(u)/k for uniform u, cut at the brightest magnitude.
        double uMin = Maths.pow(10.0, COUNT_SLOPE * (params.brightestMagnitude() - params.limitingMagnitude()));
        for (int i = 0; i < n; i++) {
            double u = uMin + (1 - uMin) * rng.nextDouble();
            mag[i] = (float) (params.limitingMagnitude() + Maths.log10(u) / COUNT_SLOPE);
            bv[i] = (float) Math.clamp(rng.nextGaussian(0.6, 0.45), -0.3, 2.0);
            // Galactic latitude: sin b uniform (an even sphere), pulled towards 0 by the concentration.
            double s = 2 * rng.nextDouble() - 1;
            double sinB = Math.copySign(Maths.pow(Math.abs(s), 1 + params.planeConcentration()), s);
            double l = rng.nextDouble() * Maths.TAU;
            double cosB = Math.sqrt(1 - sinB * sinB);
            gal[0] = cosB * Maths.cos(l);
            gal[1] = cosB * Maths.sin(l);
            gal[2] = sinB;
            Galactic.toEquatorial(gal, 0, eq, 0);
            ra[i] = ApparentPlace.rightAscension(eq, 0);
            dec[i] = ApparentPlace.declination(eq, 0);
        }
        return new StarCatalogue("procedural:" + seed, n, ra, dec, new float[n], new float[n], mag, bv, new int[n],
            new String[n], new String[n]);
    }
}
