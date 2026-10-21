# Three-existing-corner meshing experiment — HOLD / NOT SHIPPING

**Rejected for topology regression. Both patches here are archival text only, not applied production code. Do not apply them to a release.** Shared production/default gates and the delivered APK remain unchanged by this experiment.

The candidate adds a triangle when exactly three existing verified depth-grid corners are present, using the unchanged `validTriangle` acceptance path. It synthesizes no point and does not widen geometric thresholds. The separate cache-integration draft includes the algorithm change plus native/Kotlin cache-identity invalidation; it is not a second patch to apply on top of the first. Its native tests passed, Kotlin tests were not run. Neither draft is shipping.

## Actual paired observations

Both fresh three-photo COMPACT core captures passed their unchanged artifact/checkpoint/reopen assertions and restored the clean source `d954ff21b633897eca63ac56cc6183e3b81c16f1`. Baseline test: 11.519 s; candidate: 11.051 s. Their cameras, sparse and dense output measurements matched. This does not certify mesh quality.

| Observation | Fresh baseline | Candidate |
|---|---:|---:|
| Mesh triangles | 6,726 | 8,896 |
| View01 truth-domain image coverage | 46.8165% | 57.2168% |
| Pixels outside truth domain | 5,339 | 7,006 |
| Covered-pixel depth residual p95, synthetic units | 0.0468573 | 0.0488787 |
| Strict noncoplanar triangle crossings | 6,176 | 9,643 |

All 6,726 original triangles remain; 2,170 triangles were added. The extra **3,467 strict crossings** comprise 2,714 old–added and 753 added–added pairs. No classified crossing was coplanar. The unchanged production analyzer, with declared float PLY coordinates parsed as float then widened to double, reproduced both native counters exactly; its candidate budget was not exceeded. These are exported-coordinate diagnostics, not an independent physical mesh oracle. Baseline already has serious defects; better image coverage does not justify worsening them.

The visibility metric uses all 294,723 analytic-truth-domain pixels in view01 at the committed camera intrinsics and the fixed view01 pose / view01→02 baseline gauge. It does not optimize masks or fit the surface. Perspective z-buffering, integer pixel centers, 45-step analytic-ray intersection and viewport clipping are unchanged. Wrong-depth geometry can still contribute projected coverage; depth residual is reported separately. Neither this coverage nor output-sample domain fraction measures 3D completeness, 360° coverage or physical accuracy. There is no quality-PASS threshold claim.

Earlier historical capture (6,734 faces, 46.8165% coverage) is a different run, not relabeled as this fresh baseline. Imported UUIDs and native ordering can cause output jitter; one pair is not a repeated-study claim. The actual matched camera/sparse/dense measurements reduce, but do not eliminate, general pairing limitations.

## Minimal reproduction boundaries

This archive stores no binary library, captured PLY/JPEG, APK, private path, or large image. `report.json` binds the raw evidence digests; `evidence/` retains sanitized measurements and stage-specific statuses. Historical `BUILD_LINKAGE_PASS_NOT_RUNTIME_TESTED` and similar statuses describe that earlier stage; later paired captures exercised those native capabilities. The unit RED abort is intentional evidence of the new expectation failing on the original algorithm, not a passing baseline unit suite. The classification report's PASS means its counters matched, not that geometry passed a quality gate.

Reuse `tools/relief_quality_validation/` for capture and fixed-gauge evaluation; do not duplicate or weaken its production assertions. For future reproduction, use separate source/build/output directories and exact pinned compiler, source, engine and JNI receipts. Build both engines with the same compiler/options, then original production JNI TU against each engine with its own exact RUNPATH. Update only the capture loader/wrapper native digest expectations and explicit configuration. Retain the full consumed-source/header/dependency provenance closure, not only a receipt filename. Each capture must import the same three committed synthetic JPEGs into a new Room fixture/cache. The source patches and evidence record the original identities; rebuilds are not assumed byte-identical across machines.

The frozen original bounded intersection diagnostic is `analysis/classify-crossings.cpp`, SHA recorded by `evidence/crossings.json`. Compile against the exact pinned `reconstruction/include` production headers (especially `mesh_topology.hpp`), using C++20, `-O2`, and one compiler process. Run with `BASELINE_MESH.ply CANDIDATE_MESH.ply`. Preserve input hashes before/after and compiler/library provenance. Limit combined compile/run to 60 s and 2 GiB address space, as in the actual receipt. It accepts only this fixture's declared float XYZ/normal/RGB triangle PLY layout; it is not a general untrusted-file validator.

`analysis/coverage.py` is a portable CLI adaptation of the actual paired coverage script: only repository/capture/measurement/output paths, exact measurement digest, and neutral status label are parameterized. All rasterization, fixed gauge, truth mask and residual math remain unchanged. **This packaged CLI adaptation was syntax-checked only, not runtime-rerun.** Its actual predecessor results are retained under `evidence/`. Supply `--repo`, `--capture`, `--measurements`, `--measurements-sha256`, and a fresh `--output` directory. Use the existing NumPy/Pillow/OpenCV Python environment, no optimized `python -O`; cap process wall time at 60 s, address space at 1 GiB, and numerical threads at one. The script also sets CPU/address bounds. Preserve toolchain, generator, fixture and input hashes. The unchanged evaluator is already in `tools/relief_quality_validation/evaluate.py`.

No repair, point hallucination, threshold relaxation, or blanket rejection of all difficult geometry is proposed by this archive. A future approach must reduce crossings while retaining supported geometry and be assessed independently before production integration.
