# New fixed synthetic cuboid benchmark

This is a NEW eight-view 160×120 synthetic image dataset, rendered with Blender 4.3.2. It is not the lost three-view 384×288 historical fixture, a photographed physical object, or evidence of reconstruction quality. Sparse registration, dense reconstruction, candidate meshing and quality comparisons are NOT_RUN.

## Fixed before rendering

The unchanged committed generator `tools/qa/visible_surface_baseline/generate.py` is copied in `source/`. `render-config.json` records its source commit/hash, exact Blender/prlimit binary hashes, seed 78424, 32 CPU Cycles samples, all eight views, two render threads, 2 GiB process address-space and 600-second CPU/wall limits. `pre-render.sha256` binds the original config and executed scripts before measurement. No render settings, camera subset, image resolution or seed were tuned.

The render completed within the preset bounds. The eight PNGs plus evaluation fixture total 244,946 bytes. `results/render-receipt.json` records exact generated hashes and runtime identities; historical absolute command paths are evidence, not prerequisites for consuming the committed images. No binaries are included.

## Reconstruction boundary

Only `observation-inputs.json` and the eight hash-pinned PNGs are reconstruction inputs. Metadata contains intrinsic camera calibration and explicit synthetic provenance, NOT poses, object geometry, visibility masks, foreground masks, truth scale or depth bounds.

`evaluation-only/fixture.json` contains rendered poses/geometry/ray witnesses and the old known-camera generator settings. These are evaluation-only. In particular, its near/far values and its camera extrinsics must NOT be fed to image-only reconstruction. The committed upstream `visible_surface_baseline/run.py` is a known-camera kernel test and is NOT the intended photographic benchmark runner.

Before baseline measurements, separately freeze an adapter using actual production sparse registration/track growth where bindings permit, the complete fixed eight-view set and unchanged acceptance settings. Registration failure must remain a result; no dropping views or tuning retries. Dense bounds come from estimated sparse geometry. Freeze the resulting baseline observations/attributes/ledger and sampling-derived scale before any candidate comparison. Whole-visible accuracy/coverage/topology use a single unchanged evaluation gauge. The old reported three-view metrics remain historical only.

## Render reproducibility and executed-wrapper limitations

The committed PNGs can be consumed directly without Blender; their decoded contents and hashes were independently checked. `source/generate.py` is the unchanged, source-pinned generator. The render tool identities and settings are frozen in `render-config.json`; other Blender builds/platforms may produce different bytes and must be labeled a new dataset, never substituted silently.

**`source/render.py` is HISTORICAL_EXECUTED_ONLY. Do not use it as an approved reusable replay wrapper.** Its exact executed bytes and original receipt are preserved. The one recorded render completed successfully, but review found two real safety gaps:

1. Log bytes and elapsed time are polled while the child runs but not rechecked after child exit, so a final overshoot could be missed.
2. A termination signal during `Popen` construction could raise before the process handle is assigned, preventing the wrapper from reliably cleaning up that child.

Therefore this package does not claim a successful independently bounded rerender or a fully safe reusable render controller. Any later hardened renderer must be a separate reviewed revision with cleanup/limit controls; it must not rewrite this historical execution evidence. The delivered fixed image assets and generator provenance do not depend on running this wrapper again.

Independent fixed-asset/truth-boundary review passed; compact receipts are in `review/`. The reviewer did not rerender the scene. The recorded original render runtime was 2.8 seconds, and its retained log was 44,103 bytes; these observed values do not remove the wrapper limitations above.
