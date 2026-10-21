# Experimental global field v4: REJECT candidate

The measurement ran successfully, but the candidate did not improve reconstruction. Preserve production behavior. This is a new interpolated observed-patch global-field surface, NOT the original SurfaceMesh representation or original verified sample vertices. No production/native implementation was changed by this measurement.

| Actual measured quantity | Exact original | Experimental v4 |
|---|---:|---:|
| Vertices / triangles | 5753 / 6741 | 44 / 24 |
| Covered truth pixels / 294723 | 137979 | 46 |
| Truth-image coverage | 46.8165023% | 0.0156079% |
| Common-support depth p95 (46 pixels) | 0.0439574071 | 0.0453097081 |
| Nonmanifold vertices | 369 | 1 |
| Nonmanifold edges | 166 | 0 |
| Boundary edges | 4873 | 44 |
| Directed winding conflicts | 0 | 0 |
| External crossing pairs | unknown: budget exceeded | 0: all 24 faces checked |

137933 previously covered truth pixels were lost; none gained. On the tiny common support, 11 pixels improved and 35 worsened. Surface-only residual summaries have different sampled populations and do not rescue this loss of support. Neither zero edge defects nor zero external crossings establishes quality or manifoldness: v4 still has a nonmanifold vertex, 44 boundary edges and 10 components.

Existing original native6190 complete crossings are retained separately; candidate native audit is unavailable, not zero. Both outputs use current c4b0ca8 metrics including vertex links and default5000-face external crossing budget; evaluator95b1a8 pins those metrics. External crossing semantics differ from native audit. No quality PASS, physical accuracy, full-controller/cache qualification, or 3D completeness claim.

## Methods and provenance

Same frozen trace-v2 snapshot d1b605/depth9fb072 and exact StageC original replay, not earlier profile-pair baseline6162. Original captured cameras and photo hashes fix the same view01 pose and positive view01–02 baseline gauge; no fitted alignment. Common mask is analytic truth AND finite depth in BOTH outputs, without residual filtering; this is conditioned support, not an independent held-out sample. Lost/gained support is retained explicitly. Sampling remains area-weighted4096 seed84001; vertical graph residual is not Euclidean distance.

Projection is the previous reviewed raster/depth/ray/truth method. The explicit new adapter drops unused RGB raster work because experimental PLY contains xyz/index only; no colors or camera results are fabricated. The projected image footprint is not 3D completeness. Existing independent whole-face witness checks24 faces against6782 source patches with bounded plane slab/raycone support, not exact plane coincidence or overall quality.

Measurement elapsed3.713102s, inputs unchanged, child60s wall/CPU,1GiB AS, one BLAS thread, fresh pycache, no optimized Python or injected loader. This is posthoc Python measurement time, not reconstruction runtime. The separate7.714560s prototype receipt includes compilation/linking/tests/two replays/two witness verifiers; scratch workspace+stack excludes input and Python verifier and is not process RSS.

## Exact source reconstruction

Use source-pins.json baseCommit/basePath. Copy committed measure.py/projection.py/run.py to a fresh external directory, verify base SHA256, apply `patch -p1 < profile-adapter-to-global-field.patch`, then verify experimentalAdapterSha256. Exact three targets were reconstructed and byte-compared; no measurement or native execution is authorized by this archive. Full runtime/config inventory remains external and hash-bound. A rerun requires independently reviewed paths, actual immutable artifacts, fresh output, exact clean published HEAD and explicit authorization; never manufacture missing evidence.

aggregate-measurements.json preserves measured summaries, full native scalar telemetry, experimental scalar provenance and independent witness. Native/patch arrays are omitted with element count and SHA256 of compact JSON (Python json.dumps separators=(',', ':'), allow_nan=False); raw report/receipt/PLY/provenance hashes bind exact original bytes. lineage-bindings.json binds host/StageC/global receipts, snapshot, original camera result, config and pre/post inventories. No raw geometry, images, large arrays, binaries or machine-path config is published. This archive is evidence, not a substitute for original provenance review.
