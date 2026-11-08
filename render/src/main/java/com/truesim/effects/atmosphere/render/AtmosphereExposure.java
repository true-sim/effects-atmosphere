package com.truesim.effects.atmosphere.render;

import com.truesim.common.math.Maths;

/**
 * Automatic exposure for the atmosphere's physical range, from noon (about 10⁴ cd/m²) to a moonless night (about
 * 10⁻⁴): the camera's EV100 ({@link AtmosphereConfig#ev100}) from a scene's log-average luminance. The key value
 * follows the scene's brightness as Krawczyk, Myszkowski and Seidel 2005 ("Lightness perception in tone reproduction
 * for high dynamic range images", Eq. 11) fit it, α = 1.03 − 2 / (2 + log10(L + 1)), so a night stays dark and a day
 * bright instead of both becoming mid-grey (Reinhard et al. 2002's scale α / L); in EV100 terms, exposure
 * 1 / (1.2 · 2^EV100) = α / L (Lagarde and de Rousiers 2014). {@link #adapt} eases a camera towards it over time.
 */
public final class AtmosphereExposure {

    /** The least EV100 it gives: darker scenes are left dark. */
    public static final double MIN_EV100 = -8;
    /** The most. */
    public static final double MAX_EV100 = 20;

    private AtmosphereExposure() {
    }

    /** Krawczyk et al. 2005's key value for a log-average luminance of {@code luminance} cd/m². */
    public static double key(double luminance) {
        return 1.03 - 2 / (2 + Maths.log10(Math.max(luminance, 0) + 1));
    }

    /** The EV100 exposing a scene of log-average luminance {@code luminance} cd/m² at its key value. */
    public static double ev100(double luminance) {
        double l = Math.max(luminance, 1e-9);
        double key = Math.max(key(l), 1e-3);
        return Maths.clamp(Maths.log2(l / (1.2 * key)), MIN_EV100, MAX_EV100);
    }

    /**
     * The EV100 {@code seconds} after {@code current}, easing towards {@code target} with time constant {@code tau}
     * seconds (exponential adaptation, in EV: symmetric in brightening and darkening).
     */
    public static double adapt(double current, double target, double seconds, double tau) {
        if (!(tau > 0)) {
            return target;
        }
        return target + (current - target) * Maths.exp(-seconds / tau);
    }
}
