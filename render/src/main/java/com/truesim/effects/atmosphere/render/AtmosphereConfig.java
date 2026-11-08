package com.truesim.effects.atmosphere.render;

import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;

/**
 * The tunables of the atmosphere kind of {@code render.sky} ({@link AtmosphereSky#ATMOSPHERE}): each a setting
 * {@code render.sky.atmosphere.<name>}. The media default to Hillaire 2020's Earth (its Table 1, as Bruneton 2017's
 * demo): Rayleigh scattering (5.802, 13.558, 33.1) per 1000 km with an 8 km scale height, Mie scattering 3.996 and
 * absorption 0.444 per 1000 km with a 1.2 km scale height and phase asymmetry 0.8, ozone absorbing (0.650, 1.881, 0.085)
 * per 1000 km in a tent 30 km wide about 25 km, a 6360 km planet with a 100 km atmosphere. The tables' sizes are the
 * paper's. Exposure is the camera's: scene color is luminance (cd/m²) times 1 / (1.2 · 2^EV100) (Lagarde and de
 * Rousiers 2014, "Moving Frostbite to Physically Based Rendering", Eq. 12), before the engine's display.exposure.
 *
 * @param planetRadius the ground's radius, km
 * @param atmosphereHeight the atmosphere's thickness, km
 * @param rayleighRed Rayleigh scattering at sea level, red, per km
 * @param rayleighGreen green
 * @param rayleighBlue blue
 * @param rayleighScaleHeight km
 * @param mieScattering Mie scattering at sea level, per km (grey)
 * @param mieAbsorption Mie absorption at sea level, per km
 * @param mieScaleHeight km
 * @param mieAnisotropy the Cornette-Shanks phase function's g
 * @param ozoneRed ozone absorption at its peak, red, per km
 * @param ozoneGreen green
 * @param ozoneBlue blue
 * @param ozoneCenter the ozone layer's peak, km
 * @param ozoneHalfWidth its tent's half width, km
 * @param groundAlbedo the ground's albedo for the light it bounces into the sky (multiple scattering, the planet seen
 *     from above)
 * @param multipleScattering whether the multiple-scattering table lights the media (off: single scattering)
 * @param transmittanceWidth the transmittance table's width
 * @param transmittanceHeight its height
 * @param multipleScatteringSize the multiple-scattering table's size, texels a side
 * @param skyViewWidth the sky-view table's width (all the way around)
 * @param skyViewHeight its height
 * @param perPixel whether the sky integrates every pixel's ray instead of reading the sky-view table (always from
 *     space)
 * @param minSteps the least steps of a sky ray
 * @param maxSteps the most
 * @param aerialPerspective whether surfaces take the atmosphere between them and the camera (the media hook)
 * @param aerialWidth the aerial perspective volume's directions around
 * @param aerialHeight its directions from the zenith to the nadir
 * @param aerialSlices its slices in distance
 * @param aerialRange its range, km (squared slices: finest near the camera)
 * @param ev100 the camera's exposure value at ISO 100: 15 for a sunny day; the night sky wants -2 to -6
 * @param sunDisc whether the sun's disc shows
 * @param moonDisc whether the moon's disc shows, and the moon lights the sky
 * @param stars whether the stars show
 * @param starsBelowSunAltitude the stars are drawn while the sun is below this altitude, degrees (they are lost in the
 *     day sky anyway)
 * @param captureWidth the sky capture's panorama width (its height half of it)
 * @param captureAngle degrees the sun or the moon move before the sky is captured again
 * @param captureChange the relative change of a light's illuminance before the sky is captured again
 * @param captureAltitude metres the camera climbs or sinks before the sky is captured again
 */
public record AtmosphereConfig(
    @Tunable(value = "the ground's radius, km", min = 1, max = 1e6) float planetRadius,
    @Tunable(value = "the atmosphere's thickness, km", min = 1, max = 1e4) float atmosphereHeight,
    @Tunable(value = "Rayleigh scattering at sea level, red, per km", min = 0, max = 10) float rayleighRed,
    @Tunable(value = "Rayleigh scattering at sea level, green, per km", min = 0, max = 10) float rayleighGreen,
    @Tunable(value = "Rayleigh scattering at sea level, blue, per km", min = 0, max = 10) float rayleighBlue,
    @Tunable(value = "Rayleigh density's scale height, km", min = 0.01, max = 1000) float rayleighScaleHeight,
    @Tunable(value = "Mie scattering at sea level, per km", min = 0, max = 10) float mieScattering,
    @Tunable(value = "Mie absorption at sea level, per km", min = 0, max = 10) float mieAbsorption,
    @Tunable(value = "Mie density's scale height, km", min = 0.01, max = 1000) float mieScaleHeight,
    @Tunable(value = "Mie phase function's asymmetry g (Cornette-Shanks)", min = -0.999, max = 0.999)
    float mieAnisotropy,
    @Tunable(value = "ozone absorption at its peak, red, per km", min = 0, max = 10) float ozoneRed,
    @Tunable(value = "ozone absorption at its peak, green, per km", min = 0, max = 10) float ozoneGreen,
    @Tunable(value = "ozone absorption at its peak, blue, per km", min = 0, max = 10) float ozoneBlue,
    @Tunable(value = "the ozone layer's peak height, km", min = 0, max = 1e4) float ozoneCenter,
    @Tunable(value = "the ozone layer's half width, km", min = 0.01, max = 1e4) float ozoneHalfWidth,
    @Tunable(value = "the ground's albedo for the light it bounces into the sky", min = 0, max = 1)
    float groundAlbedo,
    @Tunable("whether the multiple-scattering table lights the media (off: single scattering)")
    boolean multipleScattering,
    @Tunable(value = "the transmittance table's width", min = 16, max = 4096) int transmittanceWidth,
    @Tunable(value = "the transmittance table's height", min = 4, max = 4096) int transmittanceHeight,
    @Tunable(value = "the multiple-scattering table's size", min = 4, max = 256) int multipleScatteringSize,
    @Tunable(value = "the sky-view table's width", min = 16, max = 4096) int skyViewWidth,
    @Tunable(value = "the sky-view table's height", min = 8, max = 4096) int skyViewHeight,
    @Tunable("whether every sky pixel integrates its ray (the paper's high quality) instead of the sky-view table")
    boolean perPixel,
    @Tunable(value = "the least steps of a sky ray", min = 1, max = 256) int minSteps,
    @Tunable(value = "the most steps of a sky ray", min = 1, max = 256) int maxSteps,
    @Tunable("whether surfaces take the atmosphere between them and the camera (aerial perspective)")
    boolean aerialPerspective,
    @Tunable(value = "the aerial perspective volume's directions around", min = 4, max = 1024) int aerialWidth,
    @Tunable(value = "the aerial perspective volume's directions from zenith to nadir", min = 4, max = 1024)
    int aerialHeight,
    @Tunable(value = "the aerial perspective volume's slices in distance", min = 2, max = 256) int aerialSlices,
    @Tunable(value = "the aerial perspective volume's range, km", min = 0.1, max = 1e5) float aerialRange,
    @Tunable(value = "the camera's exposure value at ISO 100 (15: a sunny day)", min = -10, max = 25) float ev100,
    @Tunable("whether the sun's disc shows") boolean sunDisc,
    @Tunable("whether the moon's disc shows and the moon lights the sky") boolean moonDisc,
    @Tunable("whether the stars show") boolean stars,
    @Tunable(value = "the stars show while the sun is below this altitude, degrees", min = -90, max = 90)
    float starsBelowSunAltitude,
    @Tunable(value = "the sky capture's panorama width (its height half of it)", min = 16, max = 8192)
    int captureWidth,
    @Tunable(value = "degrees the sun or moon move before the sky is captured again", min = 0, max = 90)
    float captureAngle,
    @Tunable(value = "relative change of a light's illuminance before the sky is captured again", min = 0, max = 10)
    float captureChange,
    @Tunable(value = "metres the camera climbs or sinks before the sky is captured again", min = 0, max = 1e7)
    float captureAltitude) implements TechniqueConfig {

    /** Hillaire 2020's Earth, the paper's table sizes, a sunny day's exposure. */
    public static final AtmosphereConfig EARTH = new AtmosphereConfig(6360, 100, 5.802e-3f, 13.558e-3f, 33.1e-3f, 8,
        3.996e-3f, 0.444e-3f, 1.2f, 0.8f, 0.650e-3f, 1.881e-3f, 0.085e-3f, 25, 15, 0.3f, true, 256, 64, 32, 192, 108,
        false, 4, 32, true, 64, 32, 32, 100, 15, true, true, true, 0, 512, 0.25f, 0.01f, 50);

    public AtmosphereConfig withEv100(float value) {
        return new AtmosphereConfig(planetRadius, atmosphereHeight, rayleighRed, rayleighGreen, rayleighBlue,
            rayleighScaleHeight, mieScattering, mieAbsorption, mieScaleHeight, mieAnisotropy, ozoneRed, ozoneGreen,
            ozoneBlue, ozoneCenter, ozoneHalfWidth, groundAlbedo, multipleScattering, transmittanceWidth,
            transmittanceHeight, multipleScatteringSize, skyViewWidth, skyViewHeight, perPixel, minSteps, maxSteps,
            aerialPerspective, aerialWidth, aerialHeight, aerialSlices, aerialRange, value, sunDisc, moonDisc, stars,
            starsBelowSunAltitude, captureWidth, captureAngle, captureChange, captureAltitude);
    }

    /** Whether {@code other} has the same media and planet: the transmittance and multiple-scattering tables hold. */
    public boolean sameMedia(AtmosphereConfig other) {
        return other != null && planetRadius == other.planetRadius && atmosphereHeight == other.atmosphereHeight
            && rayleighRed == other.rayleighRed && rayleighGreen == other.rayleighGreen
            && rayleighBlue == other.rayleighBlue && rayleighScaleHeight == other.rayleighScaleHeight
            && mieScattering == other.mieScattering && mieAbsorption == other.mieAbsorption
            && mieScaleHeight == other.mieScaleHeight && mieAnisotropy == other.mieAnisotropy
            && ozoneRed == other.ozoneRed && ozoneGreen == other.ozoneGreen && ozoneBlue == other.ozoneBlue
            && ozoneCenter == other.ozoneCenter && ozoneHalfWidth == other.ozoneHalfWidth
            && groundAlbedo == other.groundAlbedo && multipleScattering == other.multipleScattering;
    }

    /** The top of the atmosphere, km from the planet's centre. */
    public float topRadius() {
        return planetRadius + atmosphereHeight;
    }
}
