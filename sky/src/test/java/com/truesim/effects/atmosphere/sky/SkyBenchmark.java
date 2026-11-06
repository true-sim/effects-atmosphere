package com.truesim.effects.atmosphere.sky;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.ApparentPlace;
import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import com.truesim.effects.atmosphere.ephemeris.Ephemeris;
import com.truesim.effects.atmosphere.ephemeris.EphemerisConfig;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** CPU costs of the sky (TRUESIM_BENCHMARK=true): the ephemeris per frame, star places per update. */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class SkyBenchmark {

    private static double median(long[] samples) {
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2] / 1e3;
    }

    @Test
    void costs() throws Exception {
        Ephemeris ephemeris = new Ephemeris(EphemerisConfig.defaults());
        ephemeris.setObserver(new Observer(47, 15, 400));
        double jd = AstroTime.julianDay(2026, 11, 6.5);
        long[] t = new long[2000];
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < t.length; i++) {
                long start = System.nanoTime();
                ephemeris.update(jd + i * 1e-5);
                t[i] = System.nanoTime() - start;
            }
        }
        System.out.printf("ephemeris update: %.2f µs%n", median(t));

        StarCatalogue catalogue;
        try (InputStream in = SkyAssets.class.getResourceAsStream("/com/truesim/effects/atmosphere/sky/stars/hyg-v41-mag7.csv")) {
            long start = System.nanoTime();
            catalogue = StarCatalogueImporter.read(in, "hyg");
            System.out.printf("catalogue import (15,598 stars): %.1f ms%n", (System.nanoTime() - start) / 1e6);
        }
        long start = System.nanoTime();
        StarField field = new StarField(catalogue);
        System.out.printf("star colours: %.1f ms%n", (System.nanoTime() - start) / 1e6);
        ApparentPlace place = new ApparentPlace();
        long[] u = new long[50];
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < u.length; i++) {
                long s = System.nanoTime();
                place.prepare(jd + i, true);
                field.update(place, jd + i, 7.0, 2.54e-6, 1);
                u[i] = System.nanoTime() - s;
            }
        }
        System.out.printf("star places, all %d stars: %.1f µs%n", field.count(), median(u));
        start = System.nanoTime();
        ProceduralStars.generate(StarsConfig.forQuality(Quality.LOW).procedural(), 1);
        System.out.printf("procedural stars (2,000): %.1f ms%n", (System.nanoTime() - start) / 1e6);
    }
}
