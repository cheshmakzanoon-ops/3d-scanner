# M13 — Faster CPU stereo without weaker geometry gates

The depth hot loop now uses a precomputed relative-camera homography and one
bounded luminance cache per input view. It no longer transforms every patch
sample into world coordinates or recalculates RGB luminance for every depth
hypothesis. The patch metric, plane count, uniqueness, masks, cheirality and
reciprocal-depth checks are unchanged. Cost scratch storage is reused per view.
Additional peak cache storage is exactly four bytes per input depth pixel, plus
one small transform per camera pair (under 5 MiB at the native four-view limit).

## Measured evidence

`reports/m13-stereo-kernel.json` records five alternating Release-library timings
per variant, after warmup, on the same development host and identical real
Middlebury Motorcycle photographs. Median speedup was **3.36x** for the complete
dense-plus-mesh host call: approximately 0.504 to 0.150 seconds. This is NOT
measured phone performance and no wall-clock timing threshold is used in CI.
The output remained exactly equal at serialized precision: 4,248 dense points,
2,454 mesh vertices and 3,530 faces. No ground-truth data enters either solver.

An independent 10,000-case analytical test compares precomputed projections
against explicit ray -> world -> source-camera transformations, including
nonidentity poses, unequal focal lengths and off-center principal points.
Existing textured, occluded, empty, malformed and geometric regression cases
remain active. Non-finite NCC/uniqueness/consistency settings are now rejected.

Reproduce the comparison with a separately built M12 Release shared library:

```
python tools/benchmark_stereo_kernel.py --baseline /path/to/m12/librealityforge.so
```

Eight native CTest targets and the existing Python suite pass on the host.
Android compilation and emulator runtime must be verified on the M13 commit.
The core equations are output-compatible with existing cache keys; no geometry
or completeness improvement is claimed by this performance-only phase.

Next: use the recovered CPU budget for measured, resource-bounded higher-detail
processing and select supported cameras rather than their registration order.
Physical arm64 thermal/battery tests and complete-object validation remain open.
