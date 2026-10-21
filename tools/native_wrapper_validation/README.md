# Opt-in native wrapper validation (Linux host JVM)

Runs **seven host-only wrapper tests** against current production Kotlin wrappers and explicitly supplied
prebuilt JNI/native libraries. No Gradle, installation, download, native compilation, emulator, or
production source change is performed. It does not execute `SparsePipeline.run` end to end.

## Required existing inputs

- Current repository checkout and compiled app Kotlin classes. The entire classes tree, including
  module metadata and classes outside the engine package, is hash-bound. Sources of tested wrappers,
  frontier data types, sparse executor/publication and geometry helpers are compiled afresh.
- JDK 17 `java` executable (including its adjacent JDK `release` file).
- Kotlin compiler classpath file: existing absolute JAR paths separated by the host path separator.
- Existing Gradle module cache (`modules-2/files-2.1`) containing Kotlin 2.1.21, coroutines1.10.2,
  org.json20250517, JUnit4.13.2, Hamcrest1.3, annotations13.0. `--junit-jar` can explicitly supply
  the same JUnit version when it lives outside that cache. JAR hashes, not names alone, are bound.
- Native directory containing `librealityforge.so`, and its compatible host JNI shim.
- Explicit artifact manifest pinning both binaries and observed current native source closure.

**Provenance caveat:** artifact/source hashes establish fixed inputs, not that an arbitrary supplied
binary was built from those sources. `observe` produces an observation manifest, explicitly NOT a
build attestation. Retain independently obtained build receipts, historical source/build hashes,
compiler/build configuration and provenance alongside it. Do not relabel an unknown rebuilt binary
as source-matching merely because `observe` succeeded. No ABI/version substitution is performed.
Observed source closure includes root CMakeLists and every file under reconstruction/src and include;
this is not a hermetic build dependency inventory (system headers/toolchain/libraries are excluded).

## Usage

Set the variables below to your own existing paths; keep WORK outside REPO. No machine-specific
paths are embedded in the helper. The manifest and receipts may contain local absolute input paths.

```bash
HELPER="$REPO/tools/native_wrapper_validation/validate.py"
ARGS=(--repo "$REPO" --work "$WORK" --java "$JAVA" --cache "$MODULE_CACHE"
      --compiler-classpath "$COMPILER_CLASSPATH_FILE" --native-dir "$NATIVE_DIR"
      --shim "$JNI_SHIM" --artifact-manifest "$ARTIFACT_MANIFEST")
# Optional: --app-classes "$APP_CLASSES" --junit-jar "$JUNIT_JAR"
python "$HELPER" observe "${ARGS[@]}"  # only when manifest does not exist; observation, not attestation
python "$HELPER" evidence "${ARGS[@]}"
python "$HELPER" compile "${ARGS[@]}"
python "$HELPER" run "${ARGS[@]}"
(cd "$REPO" && python -m pytest -q tests/test_native_wrapper_validation_helper.py)
```

Compile:120s timeout,384MiB heap,2 declared active processors. Run:90s,256MiB,2 processors. Fresh
unique class outputs and success-only atomic receipts prevent stale bytecode being labeled current.
Previous receipts are archived, not overwritten silently. Input, compiler, entire app dependency tree,
JDK executable/release, manifest, helper and generated class hashes are checked before/after operations.
Run receipt is created only after JUnit success AND post-run hashes pass. Unexpected Java option
injection and LD_PRELOAD are removed; LD_LIBRARY_PATH is restricted to the explicit native directory.
The runner preloads the exact engine then shim and checks Linux process mappings for those paths and
an alternate engine basename. This is still not a fully hermetic dynamic-loader/system-library audit.

## Scope of new coverage

- `NativeFrontierGeometry.forOperation`: actual native registration and triangulation.
- `FinalSparseExecution.execute`: actual typed bundle solver; `FinalSparsePublication.prepare`:
  offside assembly preserves original anchors.
- `withNativeStageProgress`: actual progress callback, reporter cleanup and post-return quiescence;
  owner cancellation reaches the real native lease, and a subsequent native call rejects it.
- A throwing progress callback produces the exact primary error and prevents publication. Child
  work is joined before token release, preserving primary errors when cleanup also fails.

The existing `FinalSparseExecutionDeviceTest.kt` is compiled solely to reuse its synthetic fixture;
its five tests are NOT selected. Runner selects exactly the seven `ProductionWrapperHostTest` tests.
Test-only barriers leave the wrapper scope open for its 100ms reporter. They do not measure native
latency or prove interruption of an already-running kernel. Synthetic observations are not photos.

Excluded: Android NDK/Bionic/ART execution, Android org.json behavior, Room/repository, import,
decode/EXIF/color, Bitmap/Mat ownership, OpenCV/SIFT/matching, DensePreview, mesh/texture/export file
flows, full pipeline persistence/reuse, UI/GPU, physical camera geometry and device resource quality.
Use result labels that keep these host-only boundaries explicit.

## Callback cancellation correctness
Callback-originated CancellationException and reporter self-cancel-and-return cancel the owning stage
before result publication, preserving the original cancellation object. A post-callback ensureActive
observes self-cancellation. The existing finished flag is the teardown boundary: cancellation caught
after block completion is reporter cleanup, not a new stage failure. Regressions also verify normal
suspended-callback shutdown preserves successful results/lease reuse and actual JNI input failure
remains primary when racing callback cancellation. These are lifecycle/correctness checks, not
in-flight kernel interruption or performance measurements.
