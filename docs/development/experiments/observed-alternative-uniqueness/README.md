# Visible-surface retention after uniqueness correction

Baseline ad475bd, isolated correction, and integrated candidate produce identical raw JSON and all metrics on the exact published synthetic cuboid. This is retention evidence, not improved geometry or a quality pass: visible-area coverage at 1% diagonal remains 33.8779%. Full identical metrics/input hashes are referenced in measurement.json rather than duplicated.

Reproduce with the existing `tools/qa/visible_surface_baseline` generator, evaluator and README. Use the publisher-checksummed official Blender binary recorded in measurement.json; the system package with the same version string failed byte reproduction and is excluded. Render once for all arms:

```sh
"$BLENDER" -b -t 2 --python "$REPO/tools/qa/visible_surface_baseline/generate.py" -- "$SCRATCH/fixture"
python "$REPO/tools/qa/visible_surface_baseline/run.py" --repo "$ARM_SOURCE" --library "$ARM_LIBRARY" --fixture "$SCRATCH/fixture" --output "$SCRATCH/arm"
```

Run each arm separately against its matching source/library and fresh output directory. Require all source/library/input integrity flags and compare raw output plus complete evaluation. Thirteen evaluator guards passed. No checkpoint scope, truth-driven fit/crop/repair, recovered-camera frontend, Android controller or physical-device qualification is implied.

## Validation boundary

The integrated native build passed all 46 CTests. The selected Python suite passed 1,658 tests and 11 subtests, with four skips and only the previously network-denied live PyPI audit deselected. Actual standalone Kotlin 2.1.21/JUnit execution passed all 21 DepthCheckpointIdentityTest/PipelineGraphTest cases. New ProductionCheckpointFilesTest integration cases require the real Android dependency closure and await hosted Gradle execution. These are not device or release qualification. `validation.json` pins the pre-documentation source tree and test-log hashes; documentation-only additions do not change measured native source.
