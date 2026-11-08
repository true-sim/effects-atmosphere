# effects-atmosphere: design

The design report for sky, atmosphere, clouds and volumetric fog is [volumetrics-design.md](volumetrics-design.md)
(engine issues [#418](https://github.com/true-sim/engine/issues/418) and
[#419](https://github.com/true-sim/engine/issues/419)); this page records how this repository implements it and what
is built. The report's "User decisions" name one repository, `true-sim/environment`; the user later split it in two:
this one (sky, atmospheric scattering, aerial perspective, sun, moon, stars, night sky, and later volumetric fog and
light shafts) and [effects-weather](https://github.com/true-sim/effects-weather) (weather, clouds, rain, lightning),
which depends on this one.

## Principles

- **The engine gets as little as possible.** Data and simulation live here and run without the engine; render
  techniques come later as technique-registry plugins (`render.sky`, `render.fog`, `render.fog.shafts`), using only
  generic engine features (listed below).
- **Everything is a setting.** Each module has configuration records with `defaults()` and `forQuality(Quality)`;
  every value stays individually overridable (`with…` copies, or the engine's settings once the plugins register
  them as `@Tunable` records).
- **Tiers.** Every component has cheaper alternatives for games that do not aim at realism, and a cheap tier never
  needs the expensive tier's data (the procedural stars need no catalogue; the analytic Milky Way needs no texture).
- **Scene data and assets.** The sky's settings and clock are D46 components on the world's environment node; the star
  catalogue is an asset loaded through `AssetCache`/`PathAssets` (or generated, for procedural stars).
- **Deterministic and allocation-free.** All math goes through common-math's fdlibm functions (the build's
  platform-independence check forbids `java.lang.Math` transcendental functions); per-frame updates allocate nothing
  (the build's allocation check and `assertAllocationFree` tests).

## What is built

### atmosphere-ephemeris

| Part | Method | Accuracy (tested) |
|---|---|---|
| Julian day, ΔT | Meeus ch. 7; Espenak and Meeus 2006 polynomials, overridable (`EphemerisConfig.deltaTSeconds`) | exact (Meeus examples); ΔT within 1 s 1900–2005, a prediction after |
| Sidereal time | Meeus 12.4, apparent with the equation of the equinoxes | 1e-6° vs Meeus 12.a/b |
| Nutation, obliquity | Meeus ch. 22, the four main terms | 0.5" / 0.1" vs Meeus 22.a |
| Sun | Meeus ch. 25 (low accuracy) | 0.001° vs Meeus 25.a; 0.01° vs NREL SPA's topocentric example |
| Moon | Meeus ch. 47 (tables 47.A/B, 60 + 60 terms) | 1e-6° in latitude, 0.1 km vs Meeus 47.a |
| Topocentric parallax | Meeus ch. 40 with the observer's altitude (works in orbit) | tested against π cos h |
| Horizontal, refraction | Meeus ch. 13; Saemundsson (Meeus 16.4) with pressure and temperature | SPA example within 0.01° |
| Moon phase | Meeus ch. 48: phase angle, lit fraction, bright limb; principal phase times by iteration | Meeus 48.a; Meeus 49.a and USNO 2024 phase times within 2 minutes |
| Star places | proper motion, precession (Meeus 21.2–21.4) as one matrix, nutation and annual aberration (Meeus 23) | Meeus 21.b within 0.05", 23.a within 1" |
| Galactic frame | the Hipparcos (ESA SP-1200) rotation | galactic centre and pole to 1e-4° |
| Photometry | V magnitudes to lux (2.54e-6 lux at V = 0), the sun at −26.74, the moon by phase angle (Allen) | full moon 0.2–0.4 lux, sun 1.2–1.35e5 lux |

Measured (`SkyBenchmark`, desktop CPU): `Ephemeris.update` 2.2 µs; placing all 15,598 stars 0.8 ms (once per game
hour by default; the daily rotation is a 3×3 matrix applied on the GPU).

### atmosphere-sky

- `StarCatalogue`: packed arrays sorted by magnitude (a limiting magnitude is a prefix), `StarCatalogueImporter` for
  HYG-format CSV, `SkyAssets` (asset type, the `truesim-sky` classpath mount, procedural generator references).
- `StarColor`: Ballesteros 2012 temperature from B−V; Planck spectrum against the CIE 1931 fit of Wyman et al. 2013,
  linear sRGB of luminance 1 (6500 K matches the Planckian locus to 0.003 in xy).
- `StarField`: equatorial directions of date, illuminance in lux and colour per star, packed floats for upload.
- `ProceduralStars`: the cheap tier, realistic counts (log N grows 0.48 per magnitude), colours and a galactic-plane
  concentration; deterministic per seed.
- `Airglow` (van Rhijn layer, 22 V mag/arcsec² at the zenith by default) and `MilkyWay` (analytic band in galactic
  coordinates, or a game texture): parameters as data plus the reference expressions the renderer evaluates.
- `NightSky`: the per-view state (ephemeris every frame, star places when stale); `SkySettings`/`SkyTime` and the
  components `truesim.atmosphere.sky` and `truesim.atmosphere.sky-time`.

### atmosphere-fog ([#4](https://github.com/true-sim/effects-atmosphere/issues/4))

The first render plugin: fog as the engine's media shading hook `atmosphere.fog` (engine 0.192.0's hook points), so
every way of shading (forward, the visibility buffer, transparency, particles, the sky) fogs alike.

- **Area-based, not per object.** The engine's per-instance override entries give every point of an instance its
  entry's fog for the whole ray to the camera: seen through a window from outside, the air in front of the glass took
  the room's (clear) override. The hook instead integrates along the ray through regions: the outdoor height fog,
  `FogVolume` components (boxes, ellipsoids: density, albedo, height falloff, blend distance) and `IndoorZone` components
  (outdoor fog excluded or replaced, outdoor light dimmed). `FogRegions` gathers them from the scene in world terms;
  `AtmosphereFog` (a technique per view) writes the nearest (caps and cull distance in `FogSettings`) camera-relative
  into a frame buffer each frame and its address into the hook's record, merged with the scene's values for the hook.
- **Integration.** The ray is cut at the boundaries of the regions it crosses (at most `regionsPerRay`, nearest first);
  pieces in no region are height fog alone, integrated in closed form; pieces inside regions are integrated in `steps`
  homogeneous slabs (energy-conserving: Hillaire 2015). Exact for hard-edged uniform regions at any step count.
- **Light.** Isotropic ambient radiance plus the sun through Henyey-Greenstein; zones dim the outdoor light inside.
  Shadows in the fog come from atmosphere-render's froxels (`FogFroxels`), which the hook reads up to their reach.

### atmosphere-render

The sky drawn (stages V1, V2, V9's night sky and V3's froxel core of the report's plan), engine 0.192.0.

- **Atmosphere (V1).** Hillaire 2020 implemented from the paper: transmittance (Bruneton 2017's parameterisation),
  multiple scattering (Ψ_ms, 64 spherical-Fibonacci directions a texel), sky view (the paper's latitude mapping, all
  the way around in absolute azimuth so the sun and the moon share one table) and aerial perspective. Deviations,
  each measured: steps along a ray grow quadratically (the zenith's error against the path tracer fell from 13 % to
  6-8 % at the default 32 steps); the aerial perspective volume is laid around the camera by direction (the sky-view
  mapping) and squared distance, not over the frustum, so it serves every view, eye and off-screen point; its rays are
  not stopped by the planet's sphere (terrain stands above it near the horizon: stopping them there lost about 70 % of
  a box's in-scattering 14 km away, found with `AerialPerspectiveTest`). Above the atmosphere every sky pixel
  integrates its ray (the planet lit, orbit).
- **Validation.** `ReferenceAtmosphere` path traces the same media (delta tracking, both phase functions, the ground,
  next-event estimation, every order): it stands in for the report's path-traced atmosphere (§10.1) in the tests.
  With the default settings and the sun 30° up: luminance within 3 % at the zenith and the horizon; per channel the
  multiple-scattering approximation misses 7-10 % of the horizon's blue; single scattering within 4 %. At sunset the
  red/green ratio towards the sun is within 5 %.
- **Sky capture into the Environment.** No new engine hook was needed: the panorama is a dynamic storage texture of
  `Textures` that `Environment.setPanorama` takes (a new version: filtered anew). The texture is outside the graph, so
  the capture places full memory barriers before and after writing it (`SkyCapture`).
- **Night sky (V9).** Stars from `StarField` splatted per pixel (fixed point, picolux), airglow and the analytic Milky
  Way from atmosphere-sky's data, the moon's disc lit and normalised to the ephemeris' illuminance; all through the
  atmosphere's transmittance; the moon scatters in the sky as a second light.
- **Volumetric fog (V3 core).** `VolumetricFog` over atmosphere-fog: one medium (its `fogMediumAt`) and one hook (it
  reads the froxels up to their reach, then integrates on as before), every light of the view's light list with its
  shadows (`lightIrradiance` of engine-render's lights module), the sky's mean radiance from the sky-view table (read
  back for the CPU: `Atmosphere.skyAmbient`). Not yet: temporal reprojection and jitter (§8.4), tile culling of fog
  regions (every region is tested per froxel; the caps bound it), particles injecting media.

### The atmosphere for clouds (effects-weather)

What a cloud renderer takes from here, without copying the model:

| Need | Java | Slang (`import atmosphere;`) |
|---|---|---|
| The sun's and the moon's directions and light | `Atmosphere.sunDirection`, `moonDirection`, `lightIlluminance(light)` (outside the atmosphere, scene units), `lightAtCamera` | `lightDirection(a, i)`, `lightIlluminance(a, i)` of the `AtmosphereFrame` at `atmosphere.frameAddress()` |
| Transmittance from a cloud sample to the sun (the planet's shadow included) | `Atmosphere.transmittance(light, altitudeKm)` | `transmittanceToLight(a, position, direction, radius)`, positions by `planetPosition(a, cameraRelativeMetres)` |
| Aerial perspective at the cloud's depth | the request `SkyViewLut` (`scattered`, `transmitted` volumes, `range()`) | sample them as `aerial.slang` does (`skyFrame`, `skyViewUv`, squared slices), or `segment(a, ...)` per sample |
| Sky light (ambient) | `Atmosphere.skyAmbient` (isotropic radiance), the captured environment's harmonics | the sky-view table (`SkyViewLut.skyView`, `skyViewUv`, `skyViewTexel`) |
| Multiple scattering at a height | `AtmosphereLuts` (`multipleScattering`) | `multipleScattering(a, h, mu)` |
| Units | `Atmosphere.sceneUnits()`: scene units per lux and per cd/m² | the frame's illuminances are in scene units already |

The tables are frame-scope requests: `g.request(new AtmosphereLuts(atmosphere))` and `g.request(new
SkyViewLut(atmosphere))` return the same images to every technique of the frame; read them `SAMPLED_COMPUTE` and take
`atmosphere.frameAddress()` when recording (the frame's copy). `AtmosphereSky` draws at `Order.SKY`; a cloud pass
composites over the sky at its depth with the aerial perspective above it (§9.3).

### Engine follow-ups found here (none blocks)

- `display.exposure` spans 0.01-64 (12.6 stops); a day to a moonless night is about 20. The atmosphere's EV100
  setting carries the physical exposure instead (games scale their own lights by `sceneUnits`); an EV-based camera
  exposure in the engine would serve every library.
- `Environment` takes panoramas from textures only; a GPU-written source declared to the graph (an image the filtering
  reads) would replace the capture's manual barriers and could time-slice the filtering: today every capture filters
  the whole cube chain in its frame (0.74 ms, the benchmark).
- Per-light transmittance in the shadow table (#120) is still needed for cloud shadows on the sun.

## Tiers and quality presets

Costs: CPU costs are measured here; GPU costs of what is built are measured (`AtmosphereBenchmark`, RX 9070 XT,
1920 x 1080, medians; README); the rest are the design report's estimates (marked est.).

| Component | Tiers (technique kinds, cheapest last) | Expected cost |
|---|---|---|
| Sky / atmosphere (`render.sky`) | **atmosphere** (built): Hillaire 2020 tables + sky view + per-pixel option · **analytic**: a fitted clear-sky model (Preetham 1999 or Hosek–Wilkie 2012), labelled approximate, ground level only (not built) · **gradient** (built): two colours by sun height · **panorama** (the engine's `SkyBackground` in place of `AtmosphereSky`) · off | sky view 0.035 + sky 0.082 ms (per pixel: 1.18 ms); tables only on change (0.06 ms); capture 0.009 ms + the environment's filtering 0.74 ms, only on change · gradient 0.06 ms · as the engine |
| Sun and moon | ephemeris (always; CPU 2 µs) with disc + limb darkening and moon phase; or a game-set fixed direction | CPU 2 µs; GPU discs negligible |
| Stars (`StarsConfig.Source`) | **catalogue** (HYG, proper motion, precession, colours) · **procedural** (no data file) · **texture** (a sky map; not drawn yet) · off | CPU 0.8 ms per update of 15.6 k stars, about once a game hour; GPU: splat 0.005 ms + clearing the per-pixel buffer 0.011 ms (1080p) · same · - · 0 |
| Airglow | van Rhijn layer · off | per-pixel term in the sky pass, < 0.01 ms est. |
| Milky Way | analytic band · texture · off | < 0.01 ms est. · one texture fetch |
| Aerial perspective | volume about the camera (64 x 32 x 32, built) · analytic height fog · off | 0.068 ms for the volume (shared by views) + about 0.2 ms in the shading's hook at 1080p (measured: frame 2.35 against 2.06 ms without) |
| Volumetric fog (`render.fog`) | **froxel** (built: 160 x 90 x 64, every light, shadows) · **raymarch** (HQ, ray-traced shadows; later) · **analytic** (atmosphere-fog's hook, built) · off | 0.07 ms (light 0.043, integration 0.021, sky ambient 0.002) · 1–3 ms est. · in the shading |
| Light shafts (`render.fog.shafts`) | epipolar (Engelhardt–Dachsbacher) · screen-space radial blur (stylised, labelled, off by default) · off | 0.2–0.4 ms est. · ≈ 0.1 ms est. |
| Colour | RGB with fitted wavelengths · spectral sky tables (4–8 wavelengths) as an option | spectral ≈ 2× LUT cost est. |

| Preset | Stars | Airglow | Milky Way | Sky | Fog |
|---|---|---|---|---|---|
| LOW | procedural, 2,000 to mag 5.5, mean places, daily | off | off | gradient, no aerial perspective | as the game chooses (`render.fog`) |
| MEDIUM | catalogue to 6.0, mean places, daily | on | analytic | atmosphere, sky view 128 x 72, aerial 32 x 16 x 16 | froxel, 64 slices |
| HIGH | catalogue to 6.5, apparent places, hourly | on | analytic | atmosphere, the paper's sizes, aerial 64 x 32 x 32 | froxel, 64 slices |
| ULTRA | catalogue to 7.0, apparent places, hourly | on | analytic (texture if the game has one) | atmosphere per pixel (48 steps), aerial 128 x 64 x 48 | froxel (raymarch near: later) |

The ephemeris is the same for every preset: it costs microseconds.

## Generic engine features the render plugins need

Per the user's rule, the engine gets each of these as a generic feature, not a special case for the sky. Checked
against engine `origin/main` 2d5a2a8 by searching the sources; "unclear" means not found by name and to be confirmed
by the engine's owners.

| Feature | Used for | In the engine today |
|---|---|---|
| Technique slots with kinds and `@Tunable` config records | `render.sky`, `render.fog`, `render.fog.shafts` and their tiers | yes (`TechniqueSlot`, `TechniqueKind`, `TechniqueConfig.Tunable` in `engine-settings`) |
| A new `Environment` source rendered by a technique (a cube re-filtered when it changes, time-sliced) | sky capture → diffuse SH, specular cube, probe misses | used as is: a dynamic storage texture as the panorama (`SkyCapture`); a declared GPU source and time-slicing are follow-ups |
| CPU → GPU updates of a persistent buffer each frame or on change (structured data, a few hundred KB) | star field (15.6 k × 8 floats), sun and moon parameters | yes: `FrameBuffer` (one bulk `put` when the stars move) |
| CPU → GPU region updates of persistent 2D textures (partial, budgeted) | Milky Way or star textures streamed at load; weather maps (effects-weather) | partial: `graph.Texture` is a persistent 2D image views render into, filled once from `float[]`; region updates not found |
| Persistent 3D images written by compute, kept across frames, with history | aerial-perspective volume, froxel fog and its history | transient 3D images used (`g.create` with `volume`); persistent images are 2D only, histories for the froxels' temporal pass to check |
| A shading hook to apply participating media at a surface's depth (opaque apply pass, and a helper for transparent and particle shaders) | fog and aerial perspective applied once, last | yes: media hooks (`IMediaHook`), used by `atmosphere.aerialPerspective` and `atmosphere.fog` |
| Per-light transmittance in the shadow table | cloud shadows on the sun (effects-weather), atmosphere transmittance | not present (#120 is rebuilding shadows as data) |
| Directional lights driven by data each frame | sun and moon colour and direction | yes (`Lights`) |
| Async compute and time-slicing budgets | LUT rebuilds, sky capture faces | frame graph v2 design; to confirm for plugins |

## Later stages (from the report's plan)

V0 media foundations and path-tracer media → V1 atmosphere LUTs, sky capture, sun and moon discs (first render
plugin here) → V2 aerial perspective and height fog → V3–V4 froxel fog → V9 night sky rendering (stars, airglow,
Milky Way from this data) and epipolar shafts → V10 HQ ray-marched fog. Clouds, weather and lightning (V5–V8) are in
effects-weather.

## Open points

- The local `../scene` checkout on this machine is 20 commits behind `origin/main` and lacks D46 components; the
  default build includes it and then fails to compile. Build with `-Ptruesim.scene=false` (published 0.65.0) or update
  the checkout.
- No Milky Way map is bundled: the all-sky maps considered (e.g. ESA's Gaia sky map, CC BY-SA 3.0 IGO; others with
  non-commercial terms) need a licence decision first.
- The analytic sky tier: Preetham's model is published with its coefficients; Hosek–Wilkie's data files come with
  their own licence, to be checked before use.
