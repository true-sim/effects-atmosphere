package com.truesim.effects.atmosphere.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.engine.settings.EngineSettings;
import org.junit.jupiter.api.Test;

/** The CPU side: the media's integrals in closed form, the phase functions, the settings and presets, exposure. */
class AtmosphereModelTest {

    private final AtmosphereModel model = new AtmosphereModel(AtmosphereConfig.EARTH);

    @Test
    void theZenithsOpticalDepthIsTheMediasIntegralInClosedForm() {
        AtmosphereConfig c = AtmosphereConfig.EARTH;
        double top = c.atmosphereHeight();
        double[] t = new double[3];
        model.transmittanceToTop(c.planetRadius(), 1, 4000, t);
        double[] rayleigh = {c.rayleighRed(), c.rayleighGreen(), c.rayleighBlue()};
        double[] ozone = {c.ozoneRed(), c.ozoneGreen(), c.ozoneBlue()};
        for (int ch = 0; ch < 3; ch++) {
            double depth = rayleigh[ch] * c.rayleighScaleHeight() * (1 - Math.exp(-top / c.rayleighScaleHeight()))
                + (c.mieScattering() + c.mieAbsorption()) * c.mieScaleHeight() * (1 - Math.exp(-top
                / c.mieScaleHeight())) + ozone[ch] * c.ozoneHalfWidth(); // the tent's area: its half width
            assertThat(-Math.log(t[ch])).isCloseTo(depth, within(1e-5 * depth));
        }
        // The table's 40 steps are within 0.2 % of it
        double[] coarse = new double[3];
        model.transmittanceToTop(c.planetRadius(), 1, AtmosphereModel.STEPS, coarse);
        for (int ch = 0; ch < 3; ch++) {
            assertThat(coarse[ch]).isCloseTo(t[ch], within(2e-3 * t[ch]));
        }
    }

    @Test
    void thePhaseFunctionsIntegrateToOne() {
        int n = 20_000;
        double rayleigh = 0;
        double mie = 0;
        for (int i = 0; i < n; i++) {
            double mu = -1 + (i + 0.5) * 2.0 / n;
            rayleigh += AtmosphereModel.rayleighPhase(mu) * 2 * Math.PI * 2.0 / n;
            mie += AtmosphereModel.cornetteShanksPhase(0.8, mu) * 2 * Math.PI * 2.0 / n;
        }
        assertThat(rayleigh).isCloseTo(1, within(1e-6));
        assertThat(mie).isCloseTo(1, within(2e-3));
    }

    @Test
    void theSunBehindThePlanetCastsItsShadowAndSinksGradually() {
        double[] t = new double[3];
        double r = model.bottomRadius() + 0.001;
        double radius = Math.toRadians(0.27);
        model.transmittanceToLight(r, Math.sin(Math.toRadians(-2)), radius, t);
        assertThat(t[1]).isZero();
        model.transmittanceToLight(r, Math.sin(Math.toRadians(5)), radius, t);
        double up = t[1];
        assertThat(up).isBetween(0.2, 0.8);
        model.transmittanceToLight(r, 0, radius, t); // half the disc above the horizon
        double half = t[1];
        assertThat(half).isPositive().isLessThan(up);
        // Above the atmosphere's top the sun shines through nothing straight up
        model.transmittanceToLight(model.topRadius() + 1, 1, radius, t);
        assertThat(t[0]).isCloseTo(1, within(1e-9));
    }

    @Test
    void presetsGoIntoTheGamesLayerAndThePlayerStillWins() {
        EngineSettings settings = new EngineSettings();
        AtmosphereQuality.apply(settings, Quality.LOW);
        assertThat(settings.get(AtmosphereSky.SLOT.setting())).isEqualTo("gradient");
        assertThat(AtmosphereSky.ATMOSPHERE.config(settings).aerialPerspective()).isFalse();
        AtmosphereQuality.apply(settings, Quality.ULTRA);
        AtmosphereConfig ultra = AtmosphereSky.ATMOSPHERE.config(settings);
        assertThat(ultra.perPixel()).isTrue();
        assertThat(ultra.aerialWidth()).isEqualTo(128);
        settings.set(AtmosphereSky.ATMOSPHERE.<Integer>setting("aerialWidth"), 48);
        AtmosphereQuality.apply(settings, Quality.HIGH);
        assertThat(AtmosphereSky.ATMOSPHERE.config(settings).aerialWidth()).isEqualTo(48);
        assertThat(AtmosphereSky.ATMOSPHERE.config(settings).skyViewWidth()).isEqualTo(192);
        assertThat(AtmosphereSky.ATMOSPHERE.setting("ev100").name()).isEqualTo("render.sky.atmosphere.ev100");
    }

    @Test
    void exposureFollowsTheScenesBrightnessButLeavesTheNightDark() {
        double noon = AtmosphereExposure.ev100(5000);
        double dusk = AtmosphereExposure.ev100(5);
        double night = AtmosphereExposure.ev100(0.005);
        assertThat(noon).isBetween(11.0, 14.0);
        assertThat(dusk).isLessThan(noon).isGreaterThan(night);
        assertThat(night).isLessThan(-1);
        // A night is exposed darker than a mid-grey of it: its key value is small
        assertThat(AtmosphereExposure.key(0.005)).isLessThan(0.05);
        assertThat(AtmosphereExposure.adapt(10, 0, 0.5, 0.5)).isCloseTo(10 / Math.E, within(1e-9));
        assertThat(AtmosphereExposure.adapt(10, 0, 1, 0)).isZero();
        assertThat(AtmosphereExposure.ev100(0)).isEqualTo(AtmosphereExposure.MIN_EV100);
    }

    @Test
    void sameMediaIgnoresWhatTheTablesDoNotHold() {
        AtmosphereConfig c = AtmosphereConfig.EARTH;
        assertThat(c.sameMedia(c.withEv100(3))).isTrue();
        assertThat(c.sameMedia(null)).isFalse();
        assertThat(c.topRadius()).isEqualTo(6460);
    }
}
