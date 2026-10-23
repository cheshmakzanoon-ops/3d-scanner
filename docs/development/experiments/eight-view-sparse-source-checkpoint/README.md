# Fixed eight-view production sparse adapter — source checkpoint 2 (review fixes)

**NOT COMPILED. NOT RUN. Deliberately blocked pending reviewed native/runtime capability.**
No production source or root checkout changes; no reconstruction measurement; no SDK installation/license action. This package is a small review checkpoint, not a runnable/qualified benchmark or a replacement pose solver.

## Frozen experiment

`policy.json` freezes all eight provided 160×120 PNGs and exact observation manifest bytes, declared K, unchanged production settings/COMPACT, 28 pairs, 90-second operation /120-second JUnit/600-second outer watchdog, and acceptance/failure reporting before measurement. Images are consumed directly; do not rerender the historical generator. `inputs.py` stages ONLY the explicit observation allowlist, never walks/reads evaluation truth, then validates strict schema/order/hash/intrinsics/inventory. Runtime observation directory must contain only manifest + images; source package may include evaluation truth but none enters staging. Input negative controls are not geometry measurements.

`EightViewSparsePrefixHostTest.kt` derives from the exact original recorded in `source-provenance.json`. It retains actual ImportService/AndroidImportInspector, repository/Room, job lease, original SparsePipeline.run, real feature/matching/geometry calls, COMPACT, controlled SPARSE barrier, join-before-token-disposal, cache validation/database reopen/no-publication assertions. Only external fixture, count/metadata assertions and evidence capture differ. Focal35=43.26661530556787 represents declared200px K through existing estimated-pinhole path; NO fabricated measured LensProfile. All8 must import. Incomplete registration/fallback is recorded, not relabeled full success. >=20match and >=16PnP thresholds remain production-owned; no retry tuning or dropping views.

Actual completed-pair ordinals must cover1..28 on accepted sparse output; events retain descriptor counts and geometry-entry markers (not a claim of native completion). Production result carries incremental attempts and remaining IDs, final BA/fallback and track accounting. Captures include actual accepted alignment/match artifacts, exact original parent manifests/input snapshots and separately identified rebound checkpoint. Photo UUID→original SHA→fixture mapping is recorded before reconstruction. All8 initial registration does NOT prove incremental growth ran; report actual addedCameraCount. Sparse success is NOT visible-surface/dense/mesh quality.

`OpaqueBitmapBridge` is the unchanged explicit opaque Bitmap→Mat platform substitute. No fake SIFT, fabricated tracks/cameras, fake loader, fake Room or fake resource-health success. Ordinary Android graphics/SQLite HOST runtime is not physical-device evidence.

## Mandatory next capability gate (not implemented as a bypass)

`CandidateNativeLoaderTokenHostTest.kt` unconditionally refuses execution; current artifact pins are explicitly UNQUALIFIED. Original loader ownership/mapping/token checks remain as review template. Replace refusal/pins only in a newly reviewed source checkpoint after current source-bound engine+JNI shim and official OpenCV4.12 JNI provenance are established. Do not reuse obsolete historical 2/3-view native pins. Source provenance alone does not prove binaries match source.

Needed runtime: JDK17, actual current app classes including Room-generated DB/DAOs; Kotlin2.1.21/coroutines; Room2.7.1/AndroidX/transitives from actual app test runtime; JUnit4; Robolectric4.14.1 with native graphics/SQLite and pinned API35 android-all-15-robolectric-12650502.jar (SHA6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b); official OpenCV4.12 AAR Java classes and Linux JNI; source-bound engine and jni.cpp shim containing calibrated initializer and typed BA entrypoints. No export/publication shim needed. Original AAR class owner uniqueness must be verified; transformed duplicate excluded only after byte equality. Same sandbox owns actual native classes; no parent preload. No global loader changes.

Prefer source-bound current app/runtime artifacts exported by existing authorized standard CI, or run bounded adapter in that CI. No unsupported standalone fake Room/Android replacement. New SDK licenses require separate authority; this package does not install an SDK. Restored local tools currently lack Gradle/AndroidX/Room/Robolectric/OpenCV/shim. A reviewed launcher/classpath inventory, capability manifest, source/native pre/post hashes, staged-input hashes, bounded process-tree watchdog, fresh evidence directory and terminal receipt are still required. No command here launches JVM/native work. Historical wrappers cannot be used unchanged: their fixture/native/source pins intentionally reject this candidate.

Truth isolation here proves allowlisted transfer and truth-mutation invariance of staged bytes, not OS-level confinement. Before runtime, use a fresh allowlisted observation staging directory as the sole fixture input and audit the reconstruction input/dataflow; give truth only to a separate post-hoc evaluator; no mounts, namespaces, security settings or OS confinement are requested or claimed. No evaluate-only scale, masks, near/far or poses may enter solver. Final evaluator metrics are outside this sparse checkpoint scope.

## Source-only checks

```
# Run from the adapter directory; pass the absolute committed fixture path.
cd /path/to/new-eight-view-sparse-adapter
RF_EIGHT_FIXTURE=/absolute/repo/docs/development/experiments/new-cuboid-eight-view-160x120 \
  PYTHONDONTWRITEBYTECODE=1 python -m unittest -v test_inputs
```

14 tests passed in normal and optimized Python after review fixes; pure evidence helper compiled and its six controls passed using existing Kotlin2.1.21/JDK17. Initial code checkpoint also passed14 input tests: exact input acceptance, added truth/pose/near/far/mask refusal, missing/reordered views, changed K/image bytes, traversal, symlink, duplicate JSON, no-truth-required and source-truth perturbation invariance. Pipeline Kotlin compile/runtime and all reconstruction outcomes remain NOT_RUN.

## Checkpoint1 review findings retained

Independent review of manifest62f8df0bc03ff4b713ae2fc01f0711860ada0b2a8a48d16dbc5c13d4939eda74 identified two source defects before any JVM/pipeline run: unguarded pair-events write in finally could bypass cleanup, and test fixture path was an unpublished machine-specific absolute path. Checkpoint2 protects both failure.json and pair-events serialization/write through `preserveEvidenceFailure`, retaining the original Throwable and adding evidence failures as suppressed. Owner cleanup then proceeds under the unchanged no-live-worker guard. Tests use RF_EIGHT_FIXTURE or the committed experiment path; exact immutable pins still reject substitutions. The prior frozen package is retained separately, not reclassified as qualified.

`EvidenceFailureHandlingCheck.kt` tests only the pure failure-preservation helper (throwing I/O, primary/suppressed identity, self-suppression, success and cleanup continuation). This does not execute or qualify SparsePipeline, Android, JNI, actual disk-full handling or resource ownership. The pipeline remains uncompiled/unexecuted.
