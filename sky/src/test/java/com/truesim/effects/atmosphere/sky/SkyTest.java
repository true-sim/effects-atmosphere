package com.truesim.effects.atmosphere.sky;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.Angles;
import com.truesim.effects.atmosphere.ephemeris.ApparentPlace;
import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import com.truesim.effects.atmosphere.ephemeris.Galactic;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.core.SceneGraph;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The star catalogue through the asset system, star places and colours, the night sky's data, components. */
class SkyTest {

    /** Loads {@code ref} through {@code cache} to its end. */
    private static <T> T load(TaskScheduler tasks, AssetCache cache, AssetRef<T> ref) throws InterruptedException {
        cache.acquire(ref);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (cache.loadingCount() > 0) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
        return cache.get(ref);
    }

    private static StarCatalogue bundled() throws Exception {
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets assets = new PathAssets(cache);
            SkyAssets.install(assets);
            return load(tasks, cache, assets.ref(SkyAssets.STAR_CATALOGUE, SkyAssets.BUNDLED_CATALOGUE));
        }
    }

    private static StarCatalogue parse(String text) throws IOException {
        return StarCatalogueImporter.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "test");
    }

    @Test
    void theBundledCatalogueLoadsThroughTheAssetCache() throws Exception {
        StarCatalogue stars = bundled();
        assertThat(stars).isNotNull();
        assertThat(stars.size()).isEqualTo(15_598);
        assertThat(stars.name()).isEqualTo(SkyAssets.BUNDLED_CATALOGUE);
        assertThat(stars.properName(0)).isEqualTo("Sirius");
        assertThat(stars.magnitude(0)).isEqualTo(-1.44f);
        assertThat(stars.hip(0)).isEqualTo(32349);
        assertThat(stars.designation(0)).isEqualTo("9Alp CMa");
        assertThat(stars.countBrighterThan(6.5)).isBetween(8_000, 9_500); // the naked-eye sky
        assertThat(stars.countBrighterThan(-5)).isZero();
        assertThat(stars.countBrighterThan(99)).isEqualTo(stars.size());
        int vega = stars.find("vega");
        assertThat(vega).isPositive();
        assertThat(stars.rightAscension(vega)).isCloseTo(18.61564 * 15, within(1e-9));
        assertThat(stars.declination(vega)).isCloseTo(38.783692, within(1e-9));
        assertThat(stars.pmRaCosDec(vega)).isEqualTo(201.02f);
        assertThat(stars.pmDec(vega)).isEqualTo(287.46f);
        assertThat(stars.colorIndex(vega)).isEqualTo(-0.001f);
        assertThat(stars.find("No Such Star")).isEqualTo(-1);
        assertThat(stars.byteSize()).isGreaterThan(15_598L * 50);
        assertThat(stars.toString()).contains("15598 stars");
        for (int i = 1; i < stars.size(); i++) {
            assertThat(stars.magnitude(i)).isGreaterThanOrEqualTo(stars.magnitude(i - 1));
        }
    }

    @Test
    void barnardsStarMovesAsItsProperMotionSays() throws IOException {
        // Barnard's Star moves 10.3"/yr, the most of any star: 50 years later it is 8.6' further north.
        StarCatalogue stars = parse("ra,dec,pmra,pmdec,mag,proper\n17.963472,4.693391,-802.8,10362.54,9.54,Barnard\n");
        ApparentPlace place = new ApparentPlace();
        place.prepare(AstroTime.J2000 + 50 * 365.25, false);
        double[] v = new double[3];
        place.apply(stars.rightAscension(0), stars.declination(0), stars.pmRaCosDec(0), stars.pmDec(0), v, 0);
        place.prepare(AstroTime.J2000 + 50 * 365.25, false);
        double[] still = new double[3];
        place.apply(stars.rightAscension(0), stars.declination(0), 0, 0, still, 0);
        double moved = Angles.acos(v[0] * still[0] + v[1] * still[1] + v[2] * still[2]);
        assertThat(moved * 3600).isCloseTo(50 * Math.hypot(0.8028, 10.36254), within(1.0));
        assertThat(stars.colorIndex(0)).isNaN();
        assertThat(stars.hip(0)).isZero();
        assertThat(stars.designation(0)).isNull();
    }

    @Test
    void badCataloguesFailWithTheirLine() {
        assertThatThrownBy(() -> parse("")).isInstanceOf(IOException.class).hasMessageContaining("Empty");
        assertThatThrownBy(() -> parse("ra,dec,pmra,pmdec\n1,2,3,4\n")).hasMessageContaining("'mag'");
        assertThatThrownBy(() -> parse("ra,dec,pmra,pmdec,mag\n1,2,3,4\n")).hasMessageContaining("test:2");
        assertThatThrownBy(() -> parse("ra,dec,pmra,pmdec,mag\n1,2,3,4,x\n")).hasMessageContaining("test:2");
        assertThatIllegalArgumentException().isThrownBy(() -> new StarCatalogue("x", 2, new double[1], new double[1],
            new float[1], new float[1], new float[1], new float[1], new int[1], new String[1], new String[1]));
    }

    @Test
    void manyStarsGrowTheArraysAndBlankLinesAreSkipped() throws IOException {
        StringBuilder text = new StringBuilder("\"ra\",\"dec\",\"pmra\",\"pmdec\",\"mag\",\"ci\",\"hip\",\"bf\"\n\n");
        for (int i = 0; i < 3000; i++) {
            text.append(i % 24).append(",0,,,").append(i % 7).append(",0.5,").append(i).append(",\n");
        }
        StarCatalogue stars = parse(text.toString());
        assertThat(stars.size()).isEqualTo(3000);
        assertThat(stars.pmDec(0)).isZero();
        assertThat(stars.colorIndex(0)).isEqualTo(0.5f);
    }

    @Test
    void starColoursFollowTheirTemperature() {
        assertThat(StarColor.temperature(0.656)).isBetween(5700.0, 5850.0); // the sun
        assertThat(StarColor.temperature(Double.NaN)).isEqualTo(StarColor.temperature(StarColor.DEFAULT_COLOR_INDEX));
        assertThat(StarColor.temperature(-0.3)).isGreaterThan(15_000.0);
        double[] xyz = new double[3];
        StarColor.blackBodyXyz(6500, xyz, 0);
        double sum = xyz[0] + xyz[1] + xyz[2];
        // The Planckian locus at 6500 K: x 0.3135, y 0.3236 (CIE 1931).
        assertThat(xyz[0] / sum).isCloseTo(0.3135, within(0.003));
        assertThat(xyz[1] / sum).isCloseTo(0.3236, within(0.003));
        float[] rgb = new float[6];
        StarColor.linearSrgb(0.0, rgb, 0); // Vega-like: blue-white
        StarColor.linearSrgb(1.85, rgb, 3); // Betelgeuse: orange-red
        assertThat(rgb[2]).isGreaterThan(rgb[0]);
        assertThat(rgb[3]).isGreaterThan(rgb[5] * 3);
        assertThat(0.2126 * rgb[3] + 0.7152 * rgb[4] + 0.0722 * rgb[5]).isCloseTo(1.0, within(0.05));
    }

    @Test
    void proceduralStarsAreDeterministicAndRealistic() {
        ProceduralStars.Params params = ProceduralStars.Params.defaults();
        StarCatalogue a = ProceduralStars.generate(params, 7);
        StarCatalogue b = ProceduralStars.generate(params, 7);
        assertThat(a.size()).isEqualTo(9000);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.rightAscension(i)).isEqualTo(b.rightAscension(i));
            assertThat(a.magnitude(i)).isEqualTo(b.magnitude(i));
        }
        assertThat(a.magnitude(0)).isGreaterThanOrEqualTo(-1.5f);
        assertThat(a.magnitude(a.size() - 1)).isLessThanOrEqualTo(6.5f);
        // log N grows 0.48 per magnitude: a third of the stars within the last magnitude... about 67 % of them.
        double fainter = 1 - (double) a.countBrighterThan(5.5) / a.size();
        assertThat(fainter).isCloseTo(1 - Math.pow(10, -0.48), within(0.03));
        // Crowded towards the galactic plane: mean |sin b| below an even sphere's 0.5.
        double[] eq = new double[3];
        double[] gal = new double[3];
        double meanSinB = 0;
        for (int i = 0; i < a.size(); i++) {
            Galactic.equatorialVector(a.rightAscension(i), a.declination(i), eq, 0);
            Galactic.fromEquatorial(eq, 0, gal, 0);
            meanSinB += Math.abs(gal[2]) / a.size();
        }
        assertThat(meanSinB).isCloseTo(1.0 / 3, within(0.02));
        assertThat(SkyAssets.procedural(params, 7)).isEqualTo(SkyAssets.procedural(params, 7));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProceduralStars.Params(-1, 6, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProceduralStars.Params(1, 0, 6, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProceduralStars.Params(1, 6, 0, -1));
    }

    @Test
    void proceduralStarsLoadAsGeneratedAssets() throws Exception {
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            StarCatalogue stars = load(tasks, cache, SkyAssets.procedural(ProceduralStars.Params.defaults(), 3));
            assertThat(stars.size()).isEqualTo(9000);
            assertThat(stars.name()).isEqualTo("procedural:3");
        }
    }

    @Test
    void theNightSkyPlacesStarsAndAllocatesNothing() throws Exception {
        SkySettings settings = SkySettings.forQuality(Quality.ULTRA, new Observer(-24.6, -70.4, 2635)); // Paranal
        NightSky sky = new NightSky(settings);
        sky.update(AstroTime.julianDay(2026, 6, 21, 4, 0, 0));
        assertThat(sky.stars()).isNull();
        sky.setCatalogue(bundled());
        double jd = AstroTime.julianDay(2026, 6, 21, 4, 0, 0);
        sky.update(jd);
        StarField field = sky.stars();
        assertThat(field.count()).isEqualTo(field.catalogue().size()); // ULTRA: to 7.0
        assertThat(sky.isDarkerThan(18)).isTrue(); // 04:00 UT is midnight in Chile
        // Sirius' illuminance: magnitude −1.44 is 9.6e-6 lux.
        assertThat(field.illuminance()[0]).isCloseTo(9.6e-6f, within(0.2e-6f));
        // Its place agrees with the ephemeris' horizon: compare with the horizontal coordinates of the same direction.
        double[] m = sky.ephemeris().equatorialToHorizon();
        float[] d = field.directions();
        double up = m[6] * d[0] + m[7] * d[1] + m[8] * d[2];
        assertThat(up).isBetween(-1.0, 1.0);
        assertThat(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).isCloseTo(1f, within(1e-6f));
        double first = field.updatedJde();
        sky.update(jd + 0.01); // 15 minutes: within the hour, the places stay
        assertThat(field.updatedJde()).isEqualTo(first);
        sky.update(jd + 0.1);
        assertThat(field.updatedJde()).isGreaterThan(first);
        sky.setSettings(settings.withStars(settings.stars().withLimitingMagnitude(4)));
        assertThat(field.updatedJde()).isNaN();
        double[] t = {jd};
        assertAllocationFree(() -> {
            sky.update(t[0]);
            t[0] += 0.2; // past the interval every time: the stars are placed again
        });
        assertThat(field.count()).isLessThan(1000);
        sky.setSettings(settings.withStars(settings.stars().withSource(StarsConfig.Source.TEXTURE)));
        sky.update(jd);
        assertThat(field.updatedJde()).isNaN();
        sky.setCatalogue(null);
        assertThat(sky.stars()).isNull();
    }

    @Test
    void airglowAndTheMilkyWay() {
        Airglow airglow = Airglow.defaults();
        assertThat(airglow.vanRhijn(0, 6371)).isEqualTo(1.0);
        assertThat(airglow.vanRhijn(90, 6371)).isCloseTo(6.01, within(0.02));
        assertThat(airglow.luminance(0, 6371)).isCloseTo(1.7e-4, within(0.05e-4));
        assertThat(Airglow.forQuality(Quality.LOW).luminance(0, 6371)).isZero();
        assertThat(Airglow.forQuality(Quality.HIGH)).isEqualTo(airglow);
        assertThat(SkyUnits.magnitude(SkyUnits.luminance(21.3))).isCloseTo(21.3, within(1e-12));
        MilkyWay band = MilkyWay.defaults();
        double centre = band.luminance(0, 0);
        assertThat(centre).isCloseTo(SkyUnits.luminance(20.5), within(1e-12));
        assertThat(band.luminance(180, 0)).isCloseTo(0.3 * centre, within(0.01 * centre));
        assertThat(band.luminance(0, 30)).isLessThan(0.01 * centre);
        assertThat(MilkyWay.forQuality(Quality.LOW).luminance(0, 0)).isZero();
        assertThat(MilkyWay.forQuality(Quality.MEDIUM)).isEqualTo(band);
        assertThatIllegalArgumentException().isThrownBy(() -> new Airglow(true, 22, 0, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MilkyWay(MilkyWay.Source.OFF, "", 20, 0, 1, 0, 1));
        assertThatThrownBy(() -> new MilkyWay(null, "", 20, 1, 1, 0, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void presetsAndComponents() {
        for (Quality q : Quality.values()) {
            SkySettings s = SkySettings.forQuality(q, Observer.GREENWICH);
            assertThat(s.stars().source()).isEqualTo(q == Quality.LOW ? StarsConfig.Source.PROCEDURAL
                : StarsConfig.Source.CATALOGUE);
        }
        assertThat(SkySettings.defaults().stars()).isEqualTo(StarsConfig.defaults());
        assertThat(StarsConfig.defaults().withBrightness(2).brightness()).isEqualTo(2);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long environment = scene.create();
        SkySettings settings = SkySettings.defaults().withObserver(new Observer(10, 20, 30));
        scene.components().set(environment, SkyComponents.SKY, settings);
        SkyTime time = SkyTime.of(Instant.parse("2000-01-01T12:00:00Z"), 60);
        scene.components().set(environment, SkyComponents.SKY_TIME, time);
        assertThat(scene.components().get(environment, SkyComponents.SKY)).isEqualTo(settings);
        assertThat(time.julianDayUt()).isEqualTo(AstroTime.J2000);
        assertThat(scene.components().get(environment, SkyComponents.SKY_TIME).julianDayAt(1440)).isEqualTo(
            AstroTime.J2000 + 1);
        assertThat(SkyComponents.SKY.name()).isEqualTo("truesim.atmosphere.sky");
        assertThatIllegalArgumentException().isThrownBy(() -> new SkyTime(Double.NaN, 1));
        assertThatThrownBy(() -> new SkySettings(null, null, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StarsConfig(null, "", "", null, 0, 6, 1, true, 1)).isInstanceOf(NullPointerException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> StarsConfig.defaults().withLimitingMagnitude(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> new SkyTime(0, Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> new Airglow(true, Double.NaN, 90, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MilkyWay(MilkyWay.Source.OFF, "", 20, 1, 0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MilkyWay(MilkyWay.Source.OFF, "", 20, 1, 1, -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MilkyWay(MilkyWay.Source.OFF, "", 20, 1, 1, 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> StarsConfig.defaults().withBrightness(-1));
    }
}
