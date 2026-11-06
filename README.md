# true-sim effects-atmosphere

The sky of true-sim, as data and simulation that run without the engine: where the sun, the moon and the stars are
for any date, place and altitude (up to orbit), the moon's phase and brightness, a real star catalogue loaded through
the scene's asset system, and the night sky's airglow and Milky Way. The modules depend only on the JDK and true-sim's
own repositories (build-logic, common-math, scene), so a dedicated server, a tool or a test computes the same sky as
the game.

The render techniques (Hillaire 2020 atmosphere LUTs, sky capture, aerial perspective, volumetric fog) come later as
technique-registry plugins in this repository, once the engine has the generic hooks they need. The design, the tiers
and the plan: [docs/design.md](docs/design.md); the full design report: [docs/volumetrics-design.md](docs/volumetrics-design.md).
Weather and clouds live in [true-sim/effects-weather](https://github.com/true-sim/effects-weather), which builds on this.

| Module | Contents |
|---|---|
| `atmosphere-ephemeris` | Time scales (Julian days, ΔT), the sun (Meeus ch. 25) and the moon (Meeus ch. 47, ELP-2000/82 truncated), the moon's phase, bright limb and illumination, topocentric parallax at any altitude, refraction, sidereal time, precession, nutation and aberration for star places, the galactic frame, photometry in lux. JDK and common-math only. |
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
