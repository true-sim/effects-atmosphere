package com.truesim.effects.atmosphere.sky;

import com.truesim.common.math.Maths;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.Angles;
import java.util.Objects;

/**
 * The Milky Way's diffuse light, as data. Two tiers draw it: {@link Source#ANALYTIC}, a smooth band in galactic
 * coordinates (brightest towards the centre, falling off exponentially with galactic latitude), and
 * {@link Source#TEXTURE}, an all-sky map in galactic coordinates given by the game (none is bundled: the measured maps
 * we know of are not under a licence that allows it without checking, see the design notes). The analytic band is a
 * stylised fit to the naked-eye appearance, not measured photometry; its parameters are art settings.
 *
 * @param source the tier
 * @param texture the map's asset path for {@link Source#TEXTURE} (equirectangular, galactic l, b), empty for none
 * @param centreMagnitude the band's surface brightness towards the galactic centre, V mag/arcsec² (≈ 20.5 in Sagittarius)
 * @param latitudeScaleDegrees the exponential fall-off with |b|
 * @param longitudeScaleDegrees the Gaussian fall-off of the central bulge with |l|
 * @param discFraction the band's brightness far from the centre, relative to the centre
 * @param brightness a multiplier (1 = the model); an art override
 */
public record MilkyWay(Source source, String texture, double centreMagnitude, double latitudeScaleDegrees,
    double longitudeScaleDegrees, double discFraction, double brightness) {

    /** The Milky Way tiers. */
    public enum Source {
        OFF,
        ANALYTIC,
        TEXTURE
    }

    public MilkyWay {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(texture, "texture");
        if (!(latitudeScaleDegrees > 0) || !(longitudeScaleDegrees > 0) || !(discFraction >= 0) || !(brightness >= 0)) {
            throw new IllegalArgumentException("Not a Milky Way: scales " + latitudeScaleDegrees + ", "
                + longitudeScaleDegrees + ", disc " + discFraction + ", brightness " + brightness);
        }
    }

    /** The analytic band. */
    public static MilkyWay defaults() {
        return new MilkyWay(Source.ANALYTIC, "", 20.5, 6.0, 40.0, 0.3, 1.0);
    }

    /** Off at LOW, the analytic band otherwise (a game with a map sets {@link Source#TEXTURE}). */
    public static MilkyWay forQuality(Quality quality) {
        MilkyWay d = defaults();
        return quality == Quality.LOW ? d.withSource(Source.OFF) : d;
    }

    public MilkyWay withSource(Source newSource) {
        return new MilkyWay(newSource, texture, centreMagnitude, latitudeScaleDegrees, longitudeScaleDegrees,
            discFraction, brightness);
    }

    /**
     * The analytic band's luminance in cd/m² at galactic longitude {@code l} and latitude {@code b} (degrees), outside
     * the atmosphere; 0 unless the source is {@link Source#ANALYTIC}. Renderers evaluate the same expression.
     */
    public double luminance(double l, double b) {
        if (source != Source.ANALYTIC) {
            return 0.0;
        }
        double dl = Angles.signed(l) / longitudeScaleDegrees;
        double along = discFraction + (1 - discFraction) * Maths.exp(-dl * dl);
        double across = Maths.exp(-Math.abs(b) / latitudeScaleDegrees);
        return brightness * SkyUnits.luminance(centreMagnitude) * along * across;
    }
}
