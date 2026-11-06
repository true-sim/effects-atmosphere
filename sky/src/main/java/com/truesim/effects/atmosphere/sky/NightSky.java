package com.truesim.effects.atmosphere.sky;

import com.truesim.effects.atmosphere.ephemeris.ApparentPlace;
import com.truesim.effects.atmosphere.ephemeris.Ephemeris;

/**
 * The sky's state for one view of a world: the {@link Ephemeris} (sun, moon, phase, horizon rotation) every frame,
 * and the {@link StarField} when a catalogue is set and its places are older than the configured interval. What a
 * renderer, an audio system or game logic ("is it night?") reads. {@link #update} allocates nothing.
 *
 * <pre>{@code
 * NightSky sky = new NightSky(scene.components().get(environment, SkyComponents.SKY));
 * sky.setCatalogue(cache.get(starsRef));                     // when the asset has loaded
 * sky.update(time.julianDayAt(gameSeconds));
 * float[] stars = sky.stars().directions();                  // equatorial of date; rotate by equatorialToHorizon()
 * }</pre>
 */
public final class NightSky {

    private final Ephemeris ephemeris;
    private final ApparentPlace place = new ApparentPlace();
    private SkySettings settings;
    private StarField stars;

    public NightSky(SkySettings settings) {
        this.ephemeris = new Ephemeris(settings.ephemeris());
        setSettings(settings);
    }

    public SkySettings settings() {
        return settings;
    }

    /** Takes new settings; the star places are recomputed at the next update. */
    public void setSettings(SkySettings settings) {
        this.settings = settings;
        ephemeris.setConfig(settings.ephemeris());
        ephemeris.setObserver(settings.observer());
        if (stars != null) {
            stars.invalidate();
        }
    }

    /** Uses {@code catalogue}'s stars (null for none), e.g. once the asset cache has loaded it. */
    public void setCatalogue(StarCatalogue catalogue) {
        stars = catalogue == null ? null : new StarField(catalogue);
    }

    /** Computes the sky for the UT Julian day {@code jdUt}. */
    public void update(double jdUt) {
        ephemeris.update(jdUt);
        StarsConfig config = settings.stars();
        if (stars == null || config.source() == StarsConfig.Source.OFF || config.source() == StarsConfig.Source.TEXTURE) {
            return;
        }
        double jde = ephemeris.julianDayTt();
        double age = Math.abs(jde - stars.updatedJde()) * 24.0;
        if (Double.isNaN(stars.updatedJde()) || age > config.updateIntervalHours()) {
            place.prepare(jde, config.apparentPlace());
            stars.update(place, jde, config.limitingMagnitude(), settings.ephemeris().zeroMagnitudeLux(),
                config.brightness());
        }
    }

    public Ephemeris ephemeris() {
        return ephemeris;
    }

    /** The star field, or null without a catalogue. */
    public StarField stars() {
        return stars;
    }

    /**
     * Whether it is night for drawing stars and airglow: the sun below {@code -sunAltitude} degrees (6 civil, 12
     * nautical, 18 astronomical twilight).
     */
    public boolean isDarkerThan(double sunAltitude) {
        return ephemeris.sun().altitude() < -sunAltitude;
    }
}
