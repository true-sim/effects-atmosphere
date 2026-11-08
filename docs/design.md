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

## What is built (branch `foundation`)

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
- **Light.** Isotropic ambient radiance plus the sun through Henyey-Greenstein; no sun shadows in the media yet (shafts
  are V9's epipolar technique); zones dim the outdoor light inside.

## Tiers and quality presets

Costs: CPU costs are measured here; GPU costs are the design report's estimates for the RX 9070 XT at 1080p (marked
est.), to be replaced by the plugins' benchmarks.

| Component | Tiers (technique kinds, cheapest last) | Expected cost |
|---|---|---|
| Sky / atmosphere (`render.sky`) | **atmosphere**: Hillaire 2020 LUTs + sky-view + per-pixel option · **analytic**: a fitted clear-sky model (Preetham 1999 or Hosek–Wilkie 2012), labelled approximate, ground level only · **gradient**: two colours by sun height · **panorama** (today's engine) · off | ≤ 0.15 ms est. · ≈ 0.02 ms est. · < 0.01 ms · as today |
| Sun and moon | ephemeris (always; CPU 2 µs) with disc + limb darkening and moon phase; or a game-set fixed direction | CPU 2 µs; GPU discs negligible |
| Stars (`StarsConfig.Source`) | **catalogue** (HYG, proper motion, precession, colours) · **procedural** (no data file) · **texture** (a sky map) · off | CPU 0.8 ms per update of 15.6 k stars, about once a game hour; GPU ≈ 0.05 ms est. for 15 k point sprites · same · ≈ 0.02 ms est. · 0 |
| Airglow | van Rhijn layer · off | per-pixel term in the sky pass, < 0.01 ms est. |
| Milky Way | analytic band · texture · off | < 0.01 ms est. · one texture fetch |
| Aerial perspective | froxel volume per view (32³) · analytic height fog · off | < 0.05 ms est. per eye · 0.02 ms est. |
| Volumetric fog (`render.fog`, later) | **froxel** · **raymarch** (HQ, ray-traced shadows) · **height** (analytic) · off | 0.3–0.8 ms est. · 1–3 ms est. · 0.02 ms est. |
| Light shafts (`render.fog.shafts`) | epipolar (Engelhardt–Dachsbacher) · screen-space radial blur (stylised, labelled, off by default) · off | 0.2–0.4 ms est. · ≈ 0.1 ms est. |
| Colour | RGB with fitted wavelengths · spectral sky tables (4–8 wavelengths) as an option | spectral ≈ 2× LUT cost est. |

| Preset | Stars | Airglow | Milky Way | Sky (planned) | Fog (planned) |
|---|---|---|---|---|---|
| LOW | procedural, 2,000 to mag 5.5, mean places, daily | off | off | gradient or analytic | height |
| MEDIUM | catalogue to 6.0, mean places, daily | on | analytic | atmosphere (LUT) | froxel, 64 slices |
| HIGH | catalogue to 6.5, apparent places, hourly | on | analytic | atmosphere (LUT) | froxel, 128 slices |
| ULTRA | catalogue to 7.0, apparent places, hourly | on | analytic (texture if the game has one) | atmosphere, per-pixel | froxel + raymarch near |

The ephemeris is the same for every preset: it costs microseconds.

## Generic engine features the render plugins need

Per the user's rule, the engine gets each of these as a generic feature, not a special case for the sky. Checked
against engine `origin/main` 2d5a2a8 by searching the sources; "unclear" means not found by name and to be confirmed
by the engine's owners.

| Feature | Used for | In the engine today |
|---|---|---|
| Technique slots with kinds and `@Tunable` config records | `render.sky`, `render.fog`, `render.fog.shafts` and their tiers | yes (`TechniqueSlot`, `TechniqueKind`, `TechniqueConfig.Tunable` in `engine-settings`) |
| A new `Environment` source rendered by a technique (a cube re-filtered when it changes, time-sliced) | sky capture → diffuse SH, specular cube, probe misses | `Environment` takes a panorama or a colour and says "later sources feed the same filtering": the hook is to be added |
| CPU → GPU updates of a persistent buffer each frame or on change (structured data, a few hundred KB) | star field (15.6 k × 7 floats), sun and moon parameters | unclear: a generic "upload this buffer region" API was not found by name |
| CPU → GPU region updates of persistent 2D textures (partial, budgeted) | Milky Way or star textures streamed at load; weather maps (effects-weather) | partial: `graph.Texture` is a persistent 2D image views render into, filled once from `float[]`; region updates not found |
| Persistent 3D images written by compute, kept across frames, with history | aerial-perspective volume, froxel fog and its history | `ImageDesc` has 3D images (depth > 1); persistence and histories per frame graph v2: to confirm |
| A shading hook to apply participating media at a surface's depth (opaque apply pass, and a helper for transparent and particle shaders) | fog and aerial perspective applied once, last | not present (planned in the report §1.1) |
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
