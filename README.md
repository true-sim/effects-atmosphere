# true-sim effects-atmosphere

The sky of true-sim, as data and simulation that run without the engine: where the sun, the moon and the stars are
for any date, place and altitude (up to orbit), the moon's phase and brightness, a real star catalogue loaded through
the scene's asset system, and the night sky's airglow and Milky Way. The modules depend only on the JDK and true-sim's
own repositories (build-logic, common-math, scene), so a dedicated server, a tool or a test computes the same sky as
the game.

The render techniques come as plugins over the engine's generic hooks: the first, `atmosphere-fog`, is fog as a media
shading hook (height fog, fog volumes, indoor zones); the others (Hillaire 2020 atmosphere LUTs, sky capture, aerial
perspective, froxel fog) follow. The design, the tiers
and the plan: [docs/design.md](docs/design.md); the full design report: [docs/volumetrics-design.md](docs/volumetrics-design.md).
Weather and clouds live in [true-sim/effects-weather](https://github.com/true-sim/effects-weather), which builds on this.

| Module | Contents |
|---|---|
| `atmosphere-ephemeris` | Time scales (Julian days, ΔT), the sun (Meeus ch. 25) and the moon (Meeus ch. 47, ELP-2000/82 truncated), the moon's phase, bright limb and illumination, topocentric parallax at any altitude, refraction, sidereal time, precession, nutation and aberration for star places, the galactic frame, photometry in lux. JDK and common-math only. |
| `atmosphere-fog` | Fog as the engine's media shading hook `atmosphere.fog`: exponential height fog, fog volumes (`FogVolume`) and indoor zones (`IndoorZone`) from scene components, integrated along every view ray through the regions it crosses, so the air outside a window is fogged up to the glass and the room behind it stays clear ([#4](https://github.com/true-sim/effects-atmosphere/issues/4)). Engine 0.192.0. |
| `atmosphere-sky` | The star catalogue as a scene asset (HYG v4.1, 15,598 stars to magnitude 7, CC BY-SA 4.0), star colours from B−V, star places for a date, procedural stars, airglow and Milky Way parameters, the sky's scene components (D46) and quality presets. |

```java
NightSky sky = new NightSky(SkySettings.forQuality(Quality.HIGH, new Observer(47.07, 15.44, 350)));
SkyAssets.install(pathAssets);                                   // mount + importer
cache.acquire(pathAssets.ref(SkyAssets.STAR_CATALOGUE, SkyAssets.BUNDLED_CATALOGUE));
// ... once loaded: sky.setCatalogue(cache.get(ref));
sky.update(AstroTime.fromEpochMillis(gameClockMillis));         // 2 µs; star places once per game hour
double sunUp = sky.ephemeris().sun().up();                      // the sun's direction, east/north/up
double lit = sky.ephemeris().moonPhase().illuminatedFraction();
```

## Fog, fog volumes and indoor zones

```java
FogRegions regions = new FogRegions(scene);               // the scene's FogVolume and IndoorZone components
port.add(new AtmosphereFog(main, regions));               // per view: the hook, and the nearest regions each frame
scene.components().set(weather, ShadingHookComponent.TYPE, new ShadingHookComponent(AtmosphereFog.HOOK,
    MaterialData.builder(AtmosphereFog.HOOK).set("density", 0.02f).set("heightFalloff", 0.05f).build().values(), true));
scene.components().set(room, IndoorZone.TYPE, IndoorZone.box(new Vec3(4, 1.5, 3)));    // the room's air: no outdoor fog
scene.components().set(hollow, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(20, 3, 20), 0.15f).withFalloff(0.5f));
// each frame, after scene.update(): regions.update();
```

- **What is fogged** depends on where the view ray runs, not on which object it ends on: the hook cuts the ray at every
  region boundary it crosses (`atmosphere.fog.regionsPerRay` regions at most, the nearest to the camera first),
  integrates pieces in no region in closed form (height fog: optical depth and in-scattering exact) and pieces inside
  regions in `atmosphere.fog.steps` homogeneous steps (Hillaire 2015; exact for hard-edged uniform regions). The engine
  takes the media once for what is seen through glass, so the room behind a window is fogged by the outdoor air in
  front of the glass only.
- **Regions:** fog volumes add a medium (density at the centre's height, height falloff, albedo, a soft edge over their
  blend distance); indoor zones take the outdoor fog out (`EXCLUDE`) or replace it by their own air (`REPLACE`), and
  dim the outdoor light reaching media inside them (`light`). Boxes or ellipsoids, placed, turned and scaled by their
  nodes. A view takes at most `atmosphere.fog.maxVolumes` and `atmosphere.fog.maxZones` (the nearest), none farther than
  `atmosphere.fog.cullDistance` (`FogSettings`).
- **Light:** an isotropic ambient radiance (the sky's) and the sun's illuminance through a Henyey-Greenstein phase
  function. Known approximation: the sun's shadows are not in the fog (no light shafts); indoor zones stand in for them
  where the outdoor light does not reach. The engine's reference path tracer takes no media (its README).
- Tests: `FogVolumesTest` (GPU, against closed-form values: a window seen from outside and from inside, with and without
  the zone; in-scattering; a replacing zone; caps; an ellipsoid and a turned, scaled box), `FogRegionsTest`.
  Pictures: `FogScreenshots` (`TRUESIM_SCREENSHOTS=true`, `-Pscreenshots=<directory>`).

## Data and licences

The bundled star catalogue is derived from the HYG Database v4.1 (David Nash), licensed CC BY-SA 4.0; its source,
version, SHA-256 and changes are in [CREDITS.md](CREDITS.md) and next to the data. The licence covers that data file
only. The algorithms are implemented from their publications (Meeus, Espenak and Meeus, Ballesteros, Wyman et al.),
listed in CREDITS.md.

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*` and `scene` are used as included builds when present (override with
`-Ptruesim.<repo>=<path>`, or `false` to use the published packages); otherwise the published packages are used. The
benchmarks run with `TRUESIM_BENCHMARK=true`.
