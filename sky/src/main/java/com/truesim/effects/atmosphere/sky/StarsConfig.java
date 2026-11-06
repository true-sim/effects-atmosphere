package com.truesim.effects.atmosphere.sky;

import com.truesim.effects.atmosphere.Quality;
import java.util.Objects;

/**
 * The stars' tunables.
 *
 * @param source where the stars come from: the tiers
 * @param catalogue the catalogue's asset path for {@link Source#CATALOGUE} ({@link SkyAssets#BUNDLED_CATALOGUE} by
 *     default)
 * @param texture a sky texture's asset path for {@link Source#TEXTURE} (an equirectangular map in J2000 equatorial
 *     coordinates), empty for none
 * @param procedural the recipe for {@link Source#PROCEDURAL}
 * @param seed the procedural stars' seed
 * @param limitingMagnitude the faintest stars drawn
 * @param brightness a multiplier on the stars' illuminance (1 = physical); an art override
 * @param apparentPlace whether places include nutation and aberration (up to 20"); off saves nothing measurable but
 *     keeps mean places
 * @param updateIntervalHours game hours between recomputing the places (proper motion and precession are slow); the
 *     daily rotation is the horizon matrix, every frame
 */
public record StarsConfig(Source source, String catalogue, String texture, ProceduralStars.Params procedural, long seed,
    double limitingMagnitude, double brightness, boolean apparentPlace, double updateIntervalHours) {

    /** The star tiers. */
    public enum Source {
        /** No stars. */
        OFF,
        /** A sky texture: cheapest to draw, fixed resolution, no per-star data. */
        TEXTURE,
        /** Generated stars with realistic counts and colours, no data file. */
        PROCEDURAL,
        /** A real catalogue (HYG, bundled), with proper motion and precession for the date. */
        CATALOGUE
    }

    public StarsConfig {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(catalogue, "catalogue");
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(procedural, "procedural");
        if (!(brightness >= 0) || !(updateIntervalHours >= 0) || Double.isNaN(limitingMagnitude)) {
            throw new IllegalArgumentException("Not a stars configuration: brightness " + brightness + ", interval "
                + updateIntervalHours + ", limit " + limitingMagnitude);
        }
    }

    /** The bundled catalogue to magnitude 6.5, apparent places, updated every game hour. */
    public static StarsConfig defaults() {
        return forQuality(Quality.HIGH);
    }

    /** LOW: 2,000 procedural stars; MEDIUM: the catalogue to 6.0; HIGH: to 6.5; ULTRA: to 7.0. */
    public static StarsConfig forQuality(Quality quality) {
        return switch (quality) {
            case LOW -> new StarsConfig(Source.PROCEDURAL, SkyAssets.BUNDLED_CATALOGUE, "",
                new ProceduralStars.Params(2000, 5.5, -1.5, 1.0), 1L, 5.5, 1.0, false, 24.0);
            case MEDIUM -> new StarsConfig(Source.CATALOGUE, SkyAssets.BUNDLED_CATALOGUE, "",
                ProceduralStars.Params.defaults(), 1L, 6.0, 1.0, false, 24.0);
            case HIGH -> new StarsConfig(Source.CATALOGUE, SkyAssets.BUNDLED_CATALOGUE, "",
                ProceduralStars.Params.defaults(), 1L, 6.5, 1.0, true, 1.0);
            case ULTRA -> new StarsConfig(Source.CATALOGUE, SkyAssets.BUNDLED_CATALOGUE, "",
                ProceduralStars.Params.defaults(), 1L, 7.0, 1.0, true, 1.0);
        };
    }

    public StarsConfig withSource(Source newSource) {
        return new StarsConfig(newSource, catalogue, texture, procedural, seed, limitingMagnitude, brightness,
            apparentPlace, updateIntervalHours);
    }

    public StarsConfig withLimitingMagnitude(double magnitude) {
        return new StarsConfig(source, catalogue, texture, procedural, seed, magnitude, brightness, apparentPlace,
            updateIntervalHours);
    }

    public StarsConfig withBrightness(double scale) {
        return new StarsConfig(source, catalogue, texture, procedural, seed, limitingMagnitude, scale, apparentPlace,
            updateIntervalHours);
    }
}
