package com.truesim.effects.atmosphere.sky;

import com.truesim.common.math.Maths;
import com.truesim.effects.atmosphere.Quality;

/**
 * Airglow: the faint light of the upper atmosphere itself (O I 557.7 nm green, Na 589 nm, OH bands), the main light of
 * a moonless sky away from cities. Modelled as a thin emitting layer: its brightness at zenith angle z grows by the van
 * Rhijn factor 1/√(1 − (R/(R+h))² sin² z), the slant path through the layer. The renderer attenuates it by the
 * atmosphere below the layer.
 *
 * <p>Sources: Roach and Gordon, <i>The Light of the Night Sky</i> (1973); Leinert et al., "The 1997 reference of diffuse
 * night sky brightness", A&amp;AS 127 (1998), whose dark-site zenith brightness (≈ 22 V mag/arcsec² in total, airglow
 * the largest part) gives the default; luminance from surface brightness by L = 10.8·10⁴·10^(−0.4 μ) cd/m².
 *
 * @param enabled whether there is airglow
 * @param zenithMagnitude its surface brightness at the zenith in V mag/arcsec² (larger is darker; solar maximum ≈ 0.5
 *     brighter)
 * @param layerHeightKm the emitting layer's height (the 557.7 nm layer peaks near 97 km, OH near 87 km)
 * @param red its linear colour, luminance-normalised by the renderer
 * @param green its linear colour
 * @param blue its linear colour
 */
public record Airglow(boolean enabled, double zenithMagnitude, double layerHeightKm, float red, float green,
    float blue) {

    public Airglow {
        if (!(layerHeightKm > 0) || Double.isNaN(zenithMagnitude)) {
            throw new IllegalArgumentException("Not an airglow: layer " + layerHeightKm + " km, " + zenithMagnitude);
        }
    }

    /** On, 22.0 mag/arcsec² at the zenith, a 90 km layer, green. */
    public static Airglow defaults() {
        return new Airglow(true, 22.0, 90.0, 0.55f, 1.0f, 0.45f);
    }

    /** Off at LOW (a dark sky needs no layer), on otherwise. */
    public static Airglow forQuality(Quality quality) {
        Airglow d = defaults();
        return quality == Quality.LOW ? new Airglow(false, d.zenithMagnitude, d.layerHeightKm, d.red, d.green, d.blue)
            : d;
    }

    /** The van Rhijn factor at zenith angle {@code zenithAngle} (degrees) for a planet of {@code planetRadiusKm}. */
    public double vanRhijn(double zenithAngle, double planetRadiusKm) {
        double ratio = planetRadiusKm / (planetRadiusKm + layerHeightKm);
        double s = Maths.sin(zenithAngle * Maths.DEGREES_TO_RADIANS);
        return 1.0 / Math.sqrt(1.0 - ratio * ratio * s * s);
    }

    /** The luminance in cd/m² at zenith angle {@code zenithAngle} (degrees), above the lower atmosphere; 0 if off. */
    public double luminance(double zenithAngle, double planetRadiusKm) {
        return enabled ? SkyUnits.luminance(zenithMagnitude) * vanRhijn(zenithAngle, planetRadiusKm) : 0.0;
    }
}
