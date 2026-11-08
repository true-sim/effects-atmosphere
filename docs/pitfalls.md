# Pitfalls: mistakes made once, so they are not made twice

Each entry is a mistake that really happened here: what went wrong, how it was established, the rule that follows,
and where the proof is. Only verified things: a measurement, a reproduced failure, a test that fails without the fix,
or a reading of the code that shows the fault. A cause not found is written as an observation.

## Rendering

**The room's sun patch was blocky because its cascade's texels were 8 cm, whatever the filter.** The screenshots'
scene gave the sun 3 cascades of 2048 texels over 400 m at the engine's practical split (0.75); through the window
film's 100-degree view the first cascade reached 35 m, so a texel was 8 cm on the room's walls (a pixel 1.7 cm at 5 m),
and the engine's 3 x 3 filter showed each texel as a step. Smoothing the filter alone (the engine's tent-weighted PCF
and PCSS) left the edges wavering with the texels: in the wall close-up film the top edge's stair steps were 1.96
pixels rms against 2.28 before; splits of 0.95 and 4096 texels (1 cm) with the filter, 0.12.
- Proof: the close-up film (`AtmosphereScreenshots`' `wall`) measured per column and frame (this PR, with
  [engine#455](https://github.com/true-sim/engine/pull/455)); the texel
  sizes from `CascadeFit`'s splits and spheres for that view.
- Rule: check a shadow's texel size where it is looked at, against the pixel and the penumbra the light's size makes
  (the sun's: 0.9 cm per metre from the caster); a wide view over a long reach needs nearly logarithmic splits.

**Froxels lit at their centres alone aliased light shafts into froxel-sized blocks once the shadows were sharp.** With
the sun's cascades fine enough to cut sharp shafts, the room's dusty air showed the froxels' 12-pixel tiles and their
slices along the shafts' edges: each froxel was lit or not as its centre was.
- Proof: `VolumetricFogShaftsTest`: under a slatted roof, centres alone are 1.1 % off the froxels' means (16 jittered
  samples each, kept over 96 frames), one jittered sample a frame kept over frames 0.40 %, still to 0.18 % from frame
  to frame.
- Rule: a froxel is a volume; sample it across itself (jittered within it, over frames or in one), not at a point.

**A setting loaded as the player's layer threw away the settings set before it.** A test harness loaded its overrides
with `EngineSettings.load(map)`, which replaces the player's layer: the scene's cascade split (0.95), set the same
way, went back to 0.75 in those runs, and their sun patches were blurred and moved.
- Proof: two stills differing only in a fog setting differed in the sun patch on the wall until the overrides went
  through `commandLine` (shadows PR).
- Rule: overrides for an experiment go in a layer of their own (`commandLine`), never through `load`.
