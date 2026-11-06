package com.truesim.effects.atmosphere.sky;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;

/**
 * The sky's assets: star catalogues load through the scene's asset cache like any other asset, from the bundled data
 * (the {@value #MOUNT} mount, on the classpath) or any mount a game adds, and procedural ones are generated assets.
 *
 * <pre>{@code
 * PathAssets assets = new PathAssets(cache);
 * SkyAssets.install(assets);                                  // mount + .csv importer for star catalogues
 * AssetRef<StarCatalogue> stars = assets.ref(SkyAssets.STAR_CATALOGUE, SkyAssets.BUNDLED_CATALOGUE);
 * cache.acquire(stars);                                       // loads in the background
 * }</pre>
 */
public final class SkyAssets {

    /** Star catalogues. */
    public static final AssetType<StarCatalogue> STAR_CATALOGUE =
        AssetType.of("star-catalogue", StarCatalogue.class, StarCatalogue::byteSize);
    /** The mount of the data bundled in this module. */
    public static final String MOUNT = "truesim-sky";
    /** The bundled catalogue: HYG v4.1, the 15,598 stars to magnitude 7.0 (CC BY-SA 4.0, see CREDITS.md). */
    public static final String BUNDLED_CATALOGUE = MOUNT + "://stars/hyg-v41-mag7.csv";

    private static final String ROOT = "/com/truesim/effects/atmosphere/sky/";

    private SkyAssets() {
    }

    /** The bundled files, read from this module's classpath resources. */
    @AllowAllocation("setting up sources")
    public static PathSource bundled() {
        return path -> SkyAssets.class.getResourceAsStream(ROOT + path);
    }

    /** Mounts the bundled data as {@value #MOUNT} and registers the star catalogue importer for {@code .csv}. */
    public static void install(PathAssets assets) {
        assets.mounts().add(MOUNT, bundled());
        assets.importers().add(STAR_CATALOGUE, "csv", new StarCatalogueImporter());
    }

    /** The reference to a procedural catalogue: generated, cached like a loaded one. */
    public static AssetRef<StarCatalogue> procedural(ProceduralStars.Params params, long seed) {
        return AssetRef.generated(STAR_CATALOGUE, ProceduralStars.GENERATOR, params, seed);
    }
}
