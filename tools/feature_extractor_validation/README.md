# Production feature extraction: opt-in HOST JNI evidence

This bounded helper compiles the actual Android-free production
`GrayFeatureExtraction.kt`, `FeatureFrame.kt`, and `LensGeometry.kt` from source,
then executes ten tests against the app AAR's Java classes and a separately
verified official OpenCV 4.12.0 Linux JNI library. It does not reuse cached app
classes or replace SIFT. See the sibling feature matcher helper's `OPENCV_BUILD.md`
for native capability provenance and prerequisites.

Inputs are deterministic **synthetic grayscale Mats**, not captured photographs.
This is not Android decode/Bitmap/color-conversion/EXIF coverage, a complete
reconstruction pipeline result, Android ABI equivalence, physical resource
qualification, or scanning-quality evidence. Android extractor instrumentation
continues to qualify the adapter separately.

The helper uses the same explicit inputs as `tools/feature_matcher_validation/validate.py`:
`compile` then `run`, with `--repo`, independent external `--work`, `--java`,
`--compiler-classpath`, `--runtime-classpath`, `--aar`, `--capability-dir`, and `--linkage-receipt`.
First run `verify_linkage.py --java PATH --capability-dir PATH --out EXTERNAL_DIR`
to create a separate `sift-linkage.json` (do not overwrite base capability files).
It checks SIFT/Feature2D/MatOfKeyPoint native signatures and symbol exports, using
the generated JAR for signature comparison only, never execution. Both the
additional receipt and original capability are bound to compile/run receipts.
It performs no downloads, native builds or Gradle invocation. Compilation is
bounded to120 seconds/384MiB/two processors; execution to90 seconds/256MiB/two
processors and two OpenCV threads. Source/toolchain/native/output hashes bind
successful receipts; stale or changed inputs fail closed.

Tests check same-process direct-SIFT parity and output order; deterministic
coordinates/descriptors; configured row cap; ARGB sampler alignment; projection
normalization; empty frames; independent cloned-descriptor lifetime; borrowed-gray
preservation after success/failures; callback failure semantics; cancellation
postkernel, every128 keypoints, and before publication; and internal admission.
They do not prove native deallocation or process memory bounds merely by reuse.

The internal core borrows its image and sampler synchronously. It assumes the
adapter has validated metadata and performed preconversion cancellation checks;
it does not add a new cancellation polling point. Setting cancellation in STARTED
or FINISHED tests postkernel cancellation semantics, not a timed asynchronous
request inside JNI. STARTED failure emits no FINISHED. FINISHED failure after successful native
work still propagates unchanged; when native work already failed, that primary
exception survives and a distinct FINISHED failure is attached as suppressed.
Neither callback is best-effort. Two fault-injection tests deliberately release
the borrowed gray Mat synchronously in STARTED to provoke an actual SIFT
empty-image failure; this is invalid-owner test injection, not supported caller
behavior or concurrent memory mutation. Tests check the native exception class
and diagnostic plus suppressed observer identity, not unobserved native identity.
