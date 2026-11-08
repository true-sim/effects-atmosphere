# true-sim effects-atmosphere

The sky of true-sim, as data and simulation that run without the engine: where the sun, the moon and the stars are
for any date, place and altitude (up to orbit), the moon's phase and brightness, a real star catalogue loaded through
the scene's asset system, and the night sky's airglow and Milky Way. The modules depend only on the JDK and true-sim's
own repositories (build-logic, common-math, scene), so a dedicated server, a tool or a test computes the same sky as
the game.

The render techniques come as plugins over the engine's generic hooks: `atmosphere-fog` is fog as a media shading
hook (height fog, fog volumes, indoor zones); `atmosphere-render` draws the sky (the Hillaire 2020 atmosphere, sky
capture into the engine's environment, aerial perspective, the sun, the moon and the night sky) and the fog in froxels
with every light's shadows. The design, the tiers
and the plan: [docs/design.md](docs/design.md); the full design report: [docs/volumetrics-design.md](docs/volumetrics-design.md).
Weather and clouds live in [true-sim/effects-weather](https://github.com/true-sim/effects-weather), which builds on this.

| Module | Contents |
|---|---|
| `atmosphere-ephemeris` | Time scales (Julian days, ΔT), the sun (Meeus ch. 25) and the moon (Meeus ch. 47, ELP-2000/82 truncated), the moon's phase, bright limb and illumination, topocentric parallax at any altitude, refraction, sidereal time, precession, nutation and aberration for star places, the galactic frame, photometry in lux. JDK and common-math only. |
| `atmosphere-fog` | Fog as the engine's media shading hook `atmosphere.fog`: exponential height fog, fog volumes (`FogVolume`) and indoor zones (`IndoorZone`) from scene components, integrated along every view ray through the regions it crosses, so the air outside a window is fogged up to the glass and the room behind it stays clear ([#4](https://github.com/true-sim/effects-atmosphere/issues/4)). Engine 0.192.0. |
| `atmosphere-render` | The atmosphere drawn ([below](#the-atmosphere-drawn)): Hillaire 2020's transmittance, multiple-scattering, sky-view and aerial-perspective tables; the sky (`render.sky`: atmosphere or gradient) with the sun's and the moon's discs, stars, airglow and the Milky Way; aerial perspective as the media hook `atmosphere.aerialPerspective`; the sky captured into the engine's `Environment` (environment light, reflections, probes); the sun and the moon as directional lights; volumetric fog in froxels over `atmosphere-fog` (`render.fog`); quality presets and automatic exposure. Engine 0.192.0. |
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
  function (`fog.lighting(atmosphere)`: the atmosphere's sun at the camera and its sky's mean radiance). With
  froxels (`fog.froxels(new VolumetricFog(atmosphere, view))`, atmosphere-render) the fog within their reach is lit by
  every light of the view's light list with its shadows (light shafts) and the hook continues from their end; without
  them the sun's shadows are not in the fog, and indoor zones stand in for them where the outdoor light does not
  reach. The engine's reference path tracer takes no media (its README).
- Tests: `FogVolumesTest` (GPU, against closed-form values: a window seen from outside and from inside, with and without
  the zone; in-scattering; a replacing zone; caps; an ellipsoid and a turned, scaled box), `FogRegionsTest`.
  Pictures: `FogScreenshots` (`TRUESIM_SCREENSHOTS=true`, `-Pscreenshots=<directory>`).

## The atmosphere drawn

```java
Atmosphere atmosphere = new Atmosphere(device, slots, graph.settings());   // render.sky.atmosphere.* settings
AtmosphereSync sky = new AtmosphereSync(scene, environmentNode, cache, paths, atmosphere); // SkyComponents, catalogue
Environment environment = Environment.uniform("sky", 0, 0, 0);
port.add(new SkyCapture(atmosphere, environment, textures))               // the sky into the environment
    .add(new EnvironmentLighting(environment, view))                       // environment light and reflections
    .add(new AtmosphereSky(atmosphere, view))                              // the sky behind the scene (render.sky)
    .add(new AerialPerspective(atmosphere, view))                          // the media hook on every shaded point
    .add(new AtmosphereFog(view, regions).froxels(new VolumetricFog(atmosphere, view)).lighting(atmosphere));
AtmosphereQuality.apply(graph.settings(), Quality.HIGH);                  // presets into the game's layer
// every frame, after the scene's update:
sky.update(gameSeconds, cameraX, cameraY, cameraZ);                        // ephemeris, stars, the frame's atmosphere
atmosphere.light(lights, 0, 1);                                            // the sun and the moon as directional lights
```

- **The atmosphere** (Hillaire 2020, "A Scalable and Production Ready Sky and Atmosphere Rendering Technique", EGSR;
  implemented from the paper, Bruneton 2017's transmittance parameterisation): Rayleigh, Mie (Cornette-Shanks phase)
  and an ozone tent, Earth by default (Hillaire's Table 1), every coefficient a setting. The transmittance (256 x 64)
  and multiple-scattering (32 x 32, 64 directions a texel: Ψ_ms = L₂ / (1 − f_ms)) tables are made when the media
  change; the sky-view table (192 x 108, the paper's latitude mapping, all the way around so the sun and the moon share
  it) and the aerial perspective volume every frame. Rays take 4 to 32 steps growing quadratically along them (finest
  where the air is densest; the paper's uniform steps needed 8 times as many for the same error at the zenith).
  From above the atmosphere (and with `perPixel`, the paper's high-quality path) every sky pixel integrates its ray:
  the planet is lit below the horizon, so orbit works.
- **Aerial perspective:** a volume laid around the camera by the sky-view table's directions and squared distance
  slices (64 x 32 x 32 over 100 km, further from high up), not over a frustum as in the paper: one volume serves every
  view and eye at the camera and points off screen (reflections, probe hits). The media hook `atmosphere.aerialPerspective`
  runs before `atmosphere.fog` (far media first). Its rays do not stop at the planet's sphere: the scene's geometry ends
  them (terrain above the sphere at the horizon).
- **Sun, moon, night:** directions from atmosphere-sky's ephemeris (the world: +x east, +y up, -z north); the sun's
  disc limb darkened (Hillaire 2016's power law, normalised so the disc gives its illuminance in every channel), the
  moon's disc lit by the sun with Lommel-Seeliger's law and normalised to the ephemeris' illuminance (its phase), a
  second light of the scattering at night (a moonlit sky); stars splatted per pixel in fixed point (energy kept at any
  resolution), airglow (van Rhijn) and the analytic Milky Way, all through the atmosphere's transmittance; stars are
  left out while the sun is up (`starsBelowSunAltitude`).
- **Units:** luminance in cd/m² (illuminance in lux) times 1 / (1.2 · 2^EV100) (`render.sky.atmosphere.ev100`, Lagarde
  and de Rousiers 2014) is scene color, before the engine's `display.exposure`; the sun and the moon reach `Lights` in
  the same units, transmitted to the camera. `AtmosphereExposure` gives an EV100 from a scene's log-average luminance
  (Krawczyk et al. 2005's key, so nights stay dark) and eases a camera towards it.
- **Sky capture** (`SkyCapture`): the sky without the discs (the lights) and stars as a panorama in a dynamic storage
  texture, handed to the `Environment` (`setPanorama`) the frame after: diffuse harmonics, GGX and sheen cubes, probe and
  cascade misses. Again only when a light moved `captureAngle` or changed `captureChange`, the camera climbed
  `captureAltitude`, the media or the exposure changed.
- **Tiers** (`render.sky`): `atmosphere` (default), `gradient` (two colours by height: no tables), `off`;
  `render.fog`: `froxel` (default) or `analytic` (the fog hook alone). Presets (`AtmosphereQuality`): LOW gradient
  without aerial perspective; MEDIUM smaller per-frame tables; HIGH the paper's sizes; ULTRA every sky pixel's ray,
  finer aerial perspective, a 1024 capture. Every value stays a setting.
- **Volumetric fog** (`VolumetricFog`; Wronski 2014, Hillaire 2015): atmosphere-fog's own media (`fogMediumAt`: height
  fog, fog volumes, indoor zones) in froxels over the view's frustum (12-pixel tiles, 64 slices from 0.5 m to 300 m,
  exponential), lit by every light of the view's light list after its shadow (the sun's cascades: shafts) and the
  sky's mean radiance, integrated front to back; the fog hook reads them up to their reach and continues as before.
  Each froxel is sampled across itself: a point jittered within it each frame (the R2 sequence, shifted per froxel),
  last frame's froxels reprojected and kept (`render.fog.froxel.history`, 0.9; bounded by the froxel's and its six
  neighbours' values this frame, against ghosts), so shaft edges are the froxels' means, smooth and still, where
  centres alone aliased them into froxel-sized blocks; or `samples` jittered points a frame (no history, each sample
  costs a froxel light pass). Points in the air take no normal: the engine's shadow lookup gives them no normal offset
  and no PCSS search.
- **The sun's and the moon's shadows are as soft as their discs:** `Atmosphere.light` gives a shadowed light its
  disc's angular radius as its shadow spec's softness (the engine's PCSS and soft rays use it), a new spec only when
  it changes by a thousandth.
- **For other libraries (clouds):** `Atmosphere` (sun and moon directions, illuminance outside the atmosphere and at
  the camera, `transmittance(light, altitudeKm)`, `skyAmbient`, `sceneUnits`, `frameAddress`), the requests
  `AtmosphereLuts` and `SkyViewLut` (the tables and the volume, shared in the frame), and the Slang module
  `atmosphere/atmosphere.slang` (`AtmosphereFrame`, `medium`, `transmittance`, `transmittanceToLight` with the planet's
  shadow, `transmittanceToSpace`, `integrate`, `segment`, `skyFrame`/`skyViewUv` to read the sky-view table and the
  aerial perspective volume, the phase functions). See docs/design.md, "The atmosphere for clouds".
- **Tests** (GPU, validated; on CI's lavapipe): the sky at the zenith and the horizon against an independent
  path-traced reference of the same media (`ReferenceAtmosphere`, every order, no tables): within 3 % of luminance,
  the blue of the horizon 7-10 % under it (the multiple-scattering approximation); single scattering within 4 %; the
  setting sun's red over green as the reference has it; the earth's shadow and night; space black and the planet from
  400 km within 9 %; a distant object's aerial perspective against the reference, and unchanged with the hook off; a
  star's and the moon's illuminance through the atmosphere, the moon lit towards the sun; environment light and a
  mirror's reflection from the captured sky, and nothing without the capture; captures only on change; the froxels
  equal to the hook in the open and shadowed under a roof; shafts under a slatted roof sampled across the froxels and
  kept over frames 2.7 times closer to the froxels' means than centres alone, and still (`VolumetricFogShaftsTest`);
  the scene's components, the catalogue as an asset, and an
  allocation-free frame.

### What it costs

`AtmosphereBenchmark` (`TRUESIM_BENCHMARK=true`; RX 9070 XT, 1920 x 1080, the landscape of the screenshots: a 60 km
terrain, rocks, the sun's cascades, ambient occlusion, reflections; HIGH unless named; medians of 240 frames after
120, from the graph's per-pass timestamps, JaCoCo off; 2026-11-08):

| Pass | GPU |
|---|---|
| transmittance + multiple-scattering tables, when the media change (else skipped) | 0.003 + 0.060 ms |
| sky-view table | 0.035 ms (0.016 at night: one light) |
| aerial perspective volume (64 x 32 x 32) | 0.067 ms (MEDIUM 32 x 16 x 16: 0.038; ULTRA 128 x 64 x 48: 0.102) |
| sky (sky-view, discs, stars, airglow, Milky Way, media hooks) | 0.081 ms by day, 0.143 at night with 8,920 stars; per pixel (ULTRA): 1.18 ms |
| stars: clearing the per-pixel buffer, splatting | 0.011 + 0.005 ms |
| gradient sky (LOW) | 0.060 ms |
| sky capture (512 x 256), when the sky changed | 0.009 ms, plus the engine's environment filtering 0.74 ms |
| fog: sky ambient, froxel light (160 x 90 x 64, every light, cascaded shadows), integration | 0.002 + 0.043 + 0.021 ms |
| fog in the room (the sun through the window): froxel light, history (reprojection, the neighbours' bounds) | 0.080 + 0.020 ms (centres alone, before: 0.068); 2 samples a frame: 0.150 + 0.020 |

A still sun costs the atmosphere about 0.23 ms a frame at HIGH (0.14 ms at LOW, 1.38 ms at ULTRA); a moving sun adds a
capture and the environment's filtering (0.75 ms) in each frame it moved more than `captureAngle` (0.25 degrees).
The aerial perspective hook costs the shading little: frames with it off took 2.111 against 2.126 ms (one run; frame
times vary by about 0.2 ms between runs). The design's estimate for the atmosphere was 0.15-0.25 ms; for froxel fog
0.3-0.8 ms (this grid with its temporal pass: about 0.12 ms). The room's sun casts 3 cascades of 4096 texels with
practical splits of 0.95 (the screenshots' scene): 0.16 ms of shadow atlas a frame while the sun moves, against 0.05
for 2048 at 0.75, for 1 cm texels on its walls instead of 8 cm (the engine's PCSS filtering adds 0.1-0.15 ms of
shading there).

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
