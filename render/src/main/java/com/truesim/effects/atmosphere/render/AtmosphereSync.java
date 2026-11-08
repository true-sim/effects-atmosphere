package com.truesim.effects.atmosphere.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.atmosphere.sky.NightSky;
import com.truesim.effects.atmosphere.sky.SkyAssets;
import com.truesim.effects.atmosphere.sky.SkyComponents;
import com.truesim.effects.atmosphere.sky.SkySettings;
import com.truesim.effects.atmosphere.sky.SkyTime;
import com.truesim.effects.atmosphere.sky.StarCatalogue;
import com.truesim.effects.atmosphere.sky.StarsConfig;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetState;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.core.SceneGraph;
import java.util.Objects;

/**
 * A world's sky from its scene: the sky's components on the node holding its environment (atmosphere-sky's
 * {@link SkyComponents#SKY} and {@link SkyComponents#SKY_TIME}) drive a {@link NightSky} (the ephemeris every frame, the
 * star places when stale) and the {@link Atmosphere}; the star catalogue its settings name is an asset, acquired in the
 * {@link AssetCache} (the bundled HYG catalogue through {@link SkyAssets#install}, a game's from its mounts, or the
 * procedural stars, generated), and released when the settings name another.
 *
 * <pre>{@code
 * SkyAssets.install(paths);                                          // once: the bundled catalogue's mount
 * AtmosphereSync sync = new AtmosphereSync(scene, environmentNode, cache, paths, atmosphere);
 * // every frame, after the scene's update, before the graph records:
 * sync.update(frame.time(), camera.position(0), camera.position(1), camera.position(2));
 * atmosphere.light(lights, 0, 1);
 * }</pre>
 *
 * <p>Without the components the sky stays as it was last (or at its defaults: Greenwich, the J2000 epoch, frozen).
 * {@link #update} allocates nothing while the settings stay.
 */
public final class AtmosphereSync implements AutoCloseable {

    private final SceneGraph scene;
    private final long node;
    private final PathAssets paths;
    private final AssetCache cache;
    private final Atmosphere atmosphere;
    private SkySettings settings;
    private NightSky sky;
    private AssetRef<StarCatalogue> stars;
    private boolean starsSet;
    private StarsConfig followed;

    /**
     * Follows the sky components of {@code node} in {@code scene}, its star catalogue loaded into {@code cache}
     * through {@code paths}, into {@code atmosphere}.
     */
    public AtmosphereSync(SceneGraph scene, long node, AssetCache cache, PathAssets paths, Atmosphere atmosphere) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.node = node;
        this.cache = Objects.requireNonNull(cache, "cache");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.atmosphere = Objects.requireNonNull(atmosphere, "atmosphere");
        settings = SkySettings.defaults();
        sky = new NightSky(settings);
    }

    /** The night sky it keeps: its ephemeris, its stars. */
    public NightSky sky() {
        return sky;
    }

    /** The star catalogue it holds now, or null. */
    public AssetRef<StarCatalogue> stars() {
        return stars;
    }

    /**
     * The sky at game time {@code gameSeconds} seen from the camera at ({@code x}, {@code y}, {@code z}) metres: the
     * components read, the catalogue followed, the night sky and the atmosphere updated.
     */
    public void update(double gameSeconds, double x, double y, double z) {
        SkySettings now = scene.components().get(node, SkyComponents.SKY);
        if (now != null && now != settings) {
            settings = now;
            sky.setSettings(now);
        }
        followCatalogue();
        SkyTime time = scene.components().get(node, SkyComponents.SKY_TIME);
        double jd = time != null ? time.julianDayAt(gameSeconds) : 2451545.0;
        sky.update(jd);
        atmosphere.update(sky, x, y, z);
    }

    /** The catalogue the settings name: acquired, set once loaded, the one before released. */
    private void followCatalogue() {
        if (settings.stars() != followed) {
            followed = settings.stars();
            AssetRef<StarCatalogue> wanted = wanted(followed);
            if (!Objects.equals(wanted, stars)) {
                swap(wanted);
            }
        }
        if (stars != null && !starsSet && cache.state(stars) == AssetState.LOADED) {
            sky.setCatalogue(cache.get(stars));
            starsSet = true;
        }
    }

    @AllowAllocation("the stars' settings changed")
    private AssetRef<StarCatalogue> wanted(StarsConfig config) {
        return switch (config.source()) {
            case CATALOGUE -> paths.ref(SkyAssets.STAR_CATALOGUE, config.catalogue());
            case PROCEDURAL -> SkyAssets.procedural(config.procedural(), config.seed());
            case OFF, TEXTURE -> null;
        };
    }

    @AllowAllocation("the stars' settings changed")
    private void swap(AssetRef<StarCatalogue> wanted) {
        if (stars != null) {
            cache.release(stars);
        }
        stars = wanted;
        starsSet = false;
        sky.setCatalogue(null);
        if (stars != null) {
            cache.acquire(stars);
        }
    }

    /** Whether the star catalogue the settings name is loaded and set (or none is named). */
    public boolean starsReady() {
        return stars == null || starsSet;
    }

    @Override
    public void close() {
        if (stars != null) {
            cache.release(stars);
            stars = null;
        }
    }
}
