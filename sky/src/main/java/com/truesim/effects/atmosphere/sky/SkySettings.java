package com.truesim.effects.atmosphere.sky;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.EphemerisConfig;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import java.util.Objects;

/**
 * A world's sky, as scene data ({@link SkyComponents#SKY} on the node that holds the world's environment): where it is
 * seen from, how the ephemerides run, and the night sky's tiers. Every part is overridable after picking a preset.
 */
public record SkySettings(Observer observer, EphemerisConfig ephemeris, StarsConfig stars, Airglow airglow,
    MilkyWay milkyWay) {

    public SkySettings {
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(ephemeris, "ephemeris");
        Objects.requireNonNull(stars, "stars");
        Objects.requireNonNull(airglow, "airglow");
        Objects.requireNonNull(milkyWay, "milkyWay");
    }

    /** The HIGH preset at Greenwich. */
    public static SkySettings defaults() {
        return forQuality(Quality.HIGH, Observer.GREENWICH);
    }

    /** The preset {@code quality} for {@code observer}. */
    public static SkySettings forQuality(Quality quality, Observer observer) {
        return new SkySettings(observer, EphemerisConfig.forQuality(quality), StarsConfig.forQuality(quality),
            Airglow.forQuality(quality), MilkyWay.forQuality(quality));
    }

    public SkySettings withObserver(Observer newObserver) {
        return new SkySettings(newObserver, ephemeris, stars, airglow, milkyWay);
    }

    public SkySettings withStars(StarsConfig newStars) {
        return new SkySettings(observer, ephemeris, newStars, airglow, milkyWay);
    }
}
