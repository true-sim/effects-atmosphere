package com.truesim.effects.atmosphere.render;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;

/**
 * The sky's quality presets (docs/design.md, "Tiers and quality presets"), written into the game's layer of the
 * settings, so a player's own values still win and every value stays overridable:
 *
 * <ul>
 *   <li>LOW: the gradient sky ({@code render.sky=gradient}), no aerial perspective, a 256 capture;
 *   <li>MEDIUM: the atmosphere with smaller per-frame tables (sky view 128 x 72, aerial perspective 32 x 16 x 16), a
 *   256 capture;
 *   <li>HIGH: the paper's sizes (sky view 192 x 108), aerial perspective 64 x 32 x 32, a 512 capture;
 *   <li>ULTRA: every sky pixel's ray integrated (the paper's high-quality path) in up to 48 steps, aerial perspective
 *   128 x 64 x 48, a 1024 capture.
 * </ul>
 *
 * <p>The transmittance and multiple-scattering tables keep the paper's sizes at every preset: they are made only when
 * the media change. The stars, airglow and Milky Way follow atmosphere-sky's presets (its {@code SkySettings}).
 */
public final class AtmosphereQuality {

    private AtmosphereQuality() {
    }

    /** Writes preset {@code quality} into the game's layer of {@code settings}. */
    public static EngineSettings apply(EngineSettings settings, Quality quality) {
        settings.set(EngineSettings.Layer.GAME, AtmosphereSky.SLOT.setting(), quality == Quality.LOW ? "gradient"
            : "atmosphere");
        boolean ultra = quality == Quality.ULTRA;
        set(settings, "aerialPerspective", quality != Quality.LOW);
        set(settings, "perPixel", ultra);
        set(settings, "maxSteps", ultra ? 48 : 32);
        set(settings, "skyViewWidth", quality == Quality.MEDIUM ? 128 : 192);
        set(settings, "skyViewHeight", quality == Quality.MEDIUM ? 72 : 108);
        set(settings, "aerialWidth", switch (quality) {
            case LOW, MEDIUM -> 32;
            case HIGH -> 64;
            case ULTRA -> 128;
        });
        set(settings, "aerialHeight", switch (quality) {
            case LOW, MEDIUM -> 16;
            case HIGH -> 32;
            case ULTRA -> 64;
        });
        set(settings, "aerialSlices", switch (quality) {
            case LOW, MEDIUM -> 16;
            case HIGH -> 32;
            case ULTRA -> 48;
        });
        set(settings, "captureWidth", switch (quality) {
            case LOW, MEDIUM -> 256;
            case HIGH -> 512;
            case ULTRA -> 1024;
        });
        return settings;
    }

    private static <T> void set(EngineSettings settings, String field, T value) {
        Setting<T> setting = AtmosphereSky.ATMOSPHERE.setting(field);
        settings.set(EngineSettings.Layer.GAME, setting, value);
    }
}
