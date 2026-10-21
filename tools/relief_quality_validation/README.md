# Three-photo synthetic quality capture — measured, not a quality gate

Extends the byte-frozen successful core source only after ALL original core/cache/manifest/artifact/reopen/no-publication assertions: copy exact persisted result JSON and sparse/dense/mesh PLY, bounded32MiB each, into new owner-declared evidence directory; bind length/SHA plus photoUUID→originalSHA sidecar. No extra metadata is injected into production result. Input originals were already checked against committed fixture hashes. Copies persist after private test fixture cleanup; no real user photographs are involved. Wrapper validates capture inventory/hashes and records them in its terminal receipt. Core test assertions, cancellation/ownership cleanup, COMPACT settings and native/class identities remain unchanged.

Main/Gradle task names change to SparsePipelineQualityCaptureHostTest/sparsePipelineQualityCaptureProbe. Init sets `rf.quality.output` to fresh evidence/quality-capture. Accepted core configuration, full447 provenance closure, strict offline resolution and bounds remain required. Existing full-core proofs are not overwritten. The exact executable sources completed actual capture and measurement; see recorded result below.

## Post-hoc evaluator and honest truth boundary

`evaluate.py` imports the existing tools.make_fixture.surface_z and geometry-quality PLY loader/area sampler; hashes generator/calibration/helpers/fixture bytes before and after. It never regenerates images or feeds positions/poses/truth into pipeline. Calibration supplies3 synthetic K/R/t/centers; generator defines analytic graph and domain |x|<1.72,|y|<1.35. JPEGs were produced using45 fixed ray-intersection iterations, texture interpolation and JPEG encoding; analytic intended truth is not independent measured physical geometry, and the renderer approximation is not quantified by this draft.

Actual camera UUIDs are mapped ONLY through captured input SHA to committed fixture filename. Dynamic anchor choice means the old two-view test's naive XYZ×baseline must not be used blindly. Let R0,t0 be estimated world-to-view01, C=-Rᵀt. Gauge is fixed once as Xtruth=s(R0 X+t0), s=known|C02-C01|/estimated|C02-C01|. Camera i becomes Ri'=RiR0ᵀ, ti'=s(ti-Ri't0). View01 is the fixture's identity camera. No ICP, surface-fit, all-camera least-squares alignment, reflection or threshold tuning is used; reference-view pose and baseline scale cannot independently validate themselves. Other camera angles/translation errors/direction dots remain reported.

For sparse/dense all output vertices and mesh fixed4096 area-weighted samples (existing seed84001), report invalid count, domain counts/fraction and absolute vertical residual |z-surface_z(x,y)| p50/p90/p95/max. Out-of-domain analytic extrapolation is clearly separate. This is NOT Euclidean closest-surface error or reference completeness. No independently frozen visibility mask/reference samples are committed, so completeness under geometry.json is not claimed. Existing topology full self-intersection gate also has5000face budget, below these6k+ meshes; do not silently expand/claim it.

Existing tests/test_pipeline.py thresholds (rotation<1deg, direction dot>.995, sparse vertical median<.08/p90<.16 synthetic units) are recorded descriptively only. That two-view path differs from full-core's selected anchor/global typed BA/frontend; dense/mesh have no matching threshold. Evaluator status is always MEASURE_ONLY_NOT_QUALITY_PASS on valid completed measurement. No physical units, scale accuracy, held-out reprojection or quality gate PASS is asserted. Actual track observations are not captured here, so only production's prior reprojection validation—not new independent reprojection evidence—remains.

After root-approved owner runtime and terminal restored PASS:

```
# config/command authority follows the existing core portable harness
python validate.py --config "$CONFIG" --config-sha256 "$CONFIG_SHA" \
  --expected-commit "$PUBLISHED_COMMIT" --output "$FRESH_EVIDENCE"
# Separate bounded measure-only step, existing NumPy/OpenCV Python environment; no installation:
# owner applies60s process timeout, pins evaluator/source files and records Python/dependency provenance.
python evaluate.py --repo "$REPO" --capture "$FRESH_EVIDENCE/quality-capture" \
  --capture-manifest-sha256 "$MANIFEST_SHA_FROM_TERMINAL_RECEIPT" --output "$NEW_REPORT"
```

Source review preceded the separately authorized actual capture/evaluator run. Native/Android/physical claims remain limited exactly as in accepted full-core test. This opt-in source package changes no production algorithms or default gates.

## Current evaluator dependency version

The current evaluator pins geometry metrics SHA `c4b0ca8bac0757645718586c842671e9bed416d7c0e41ae54bf8553fe85d5bf0`, which adds indexed vertex-link auditing. All before/after source-provenance guards remain enforced. This is a new evaluator source identity, not a rerun or recertification of the historical measurements below; their original evaluator/helper hashes and receipts remain historical. The area sampler and this evaluator's measure-only behavior are unchanged.

## Recorded run and portable execution

Source09e879462474ca00f2cae1c236036beff47fde54: actual capture1/1 passed in11.623seconds, strict artifact/cache/reopen assertions retained, source/runtime unchanged and normal compilation restored. Actual post-hoc evaluator completed in0.745seconds under1GiB address space/60sCPU/60swall/singlethread bounds, status MEASURE_ONLY_NOT_QUALITY_PASS. Captured raw assets are external evidence, not committed binaries. Exact evaluator SHA04e99a1946e2716ad55a9c51eea8ab2fcf9de33c93ff01fedcb11fddf2f85a80 and capture main056a6894293a1e8a6e8e3d310c203e900bcc8cd777d4e5161d3335aa84e5b36d are retained unchanged.

In-domain p95 vertical residuals are0.02665 sparse,0.05658 dense and0.04749 mesh synthetic units. Domain fractions are902/904,6518/6851 and3992/4096 OUTPUT samples, respectively. They are not reference coverage/completeness. Camera2/3 rotation errors are0.02577°/0.00770°; reference camera1 pose and baseline scale are gauge definitions, not independent accuracy.

Use the full reviewed [core configuration schema](../full_core_validation/README.md#explicit-configuration-and-owner-invocation), exact core native pins and complete native provenance closure. Run this package's validate.py with explicit config hash, clean expected commit and new output directory; choose only the capture task, no default-gate changes. Add this exact evaluator and truth/helper inputs to the reviewed source inventory. No native downloads/builds occur.

For separate evaluation, the owner must hash the selected Python executable, venv configuration, NumPy/OpenCV Python and shared-library files and every capture/truth/evaluator source before and after; record versions and compare unchanged. Historical runtime was Python3.12.14, NumPy2.2.6 and OpenCV4.12.0. Hashes alone do not establish truthful provenance: review the complete actual source/dependency closure. Example bounded Linux invocation after reviewing paths/config and with a fresh output parent:

```sh
# PYTHON is the reviewed existing environment executable, not a downloader.
# EVIDENCE is a new owner-created external directory; manifest SHA comes from capture receipt.
( ulimit -v 1048576; ulimit -t 60
  env -u PYTHONPATH -u PYTHONHOME -u LD_PRELOAD -u LD_LIBRARY_PATH \
    OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 MKL_NUM_THREADS=1 NUMEXPR_NUM_THREADS=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    timeout --signal=TERM --kill-after=5s 60s "$PYTHON" -X "pycache_prefix=$EVIDENCE/empty-pycache" \
    tools/relief_quality_validation/evaluate.py --repo "$REPO" --capture "$CAPTURE" \
    --capture-manifest-sha256 "$CAPTURE_MANIFEST_SHA256" --output "$EVIDENCE/measurements.json"
)
```

Run with inherited LD_*/PYTHON* injection variables rejected, not merely trusting their removal; the command illustrates process bounds, not a replacement for provenance/terminal receipts. Record exit/timeout, stdout/stderr, dependency versions/hashes, captured manifest/report hashes and pre/post equality. A partial file or timeout is a failure, never measurements success. These bounds may reject a different host setup; do not silently relax them. No modified capability is automatically trusted.

Helper tests synthesize ideal evaluator-only cameras/points to test gauge identity/known proper rotation+translation+scale and error handling; they never substitute data into SparsePipeline and are not reconstruction evidence. They do not validate renderer fidelity, independent reference visibility or real-object quality.
