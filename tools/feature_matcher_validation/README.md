# Production matcher HOST JNI gate

Opt-in Linux JVM validation of current production `FeatureMatcher`, `FeatureFrame`, lens and matching contracts using the project's exact OpenCV 4.12.0 AAR Java classes and an independently built official-source Linux 4.12.0 JNI library. Does not use cached app classes or Python matching.

Provide explicit existing inputs; the helper never downloads/builds native libraries, installs, invokes Gradle, or changes production dependencies:

```
python validate.py compile --repo "$REPO" --work "$WORK" --java "$JAVA" \
  --compiler-classpath "$COMPILER_CP_FILE" --runtime-classpath "$RUNTIME_CP_FILE" \
  --aar "$OPENCV_AAR" --capability-dir "$CAPABILITY_DIR"
python validate.py run --repo "$REPO" --work "$WORK" --java "$JAVA" \
  --compiler-classpath "$COMPILER_CP_FILE" --runtime-classpath "$RUNTIME_CP_FILE" \
  --aar "$OPENCV_AAR" --capability-dir "$CAPABILITY_DIR"
```

Classpath files contain platform-separated existing JAR paths. Compiler: Kotlin2.1.21 with its cached compiler dependencies; runtime: Kotlin2.1.21, coroutines1.10.2, JSON20250517, JUnit4.13.2, Hamcrest1.3, annotations13.0. Capability directory must contain its reviewed `validation.json`, official source archive/license, observed source manifest, build/config logs, native binary, and AAR linkage/smoke evidence matching all receipt hashes. That specific supplied artifact evidence is required; arbitrary binary hashes are not source provenance. Generated Linux Java JAR is compared in the capability receipt but never used on the test runtime classpath.

Nine tests cover inclusive descriptor512 boundary, invalid/nonfinite values, original cancellation call forms, callback behavior, whole-pair BF differential results over unequal synthetic descriptors and four block sizes, noncontiguous reverse gather/remap, many-to-one forward survivors, strict ratio/ties, ordered full coordinate/color payload, cancellation at both directions' native-call entry/return, subsequent descriptor reuse, closed ownership, and fixed FeatureFrames with opposite import-UUID ordering yielding identical physical-view row/key/ray/color payloads. Source-defined count<2 fast path behavior is not redefined.

Limits: fresh classes, compilation120s/384MiB; execution90s/256MiB; two JVM processors and OpenCV threads. Hash checks bind inputs and outputs before/after. Prior success receipts are invalidated before attempted reruns. Exact JNI mappings are checked at entry/exit.

This is synthetic-descriptor production matching on a Linux build, **not** Android Bitmap/decoder/FeatureExtractor, full SparsePipeline, physical scan quality or device performance. Cancellation checks occur before/after noninterruptible BF calls; no in-flight native interruption claim. Geometry verification uses only the existing <20 early rejection path, so scanner reconstruction JNI is not loaded or qualified here.

Official-source native prerequisite recipe and provenance schema: [OPENCV_BUILD.md](OPENCV_BUILD.md).

## Source-bound real-three HOST supplement

The separately admitted `tools/eight_view_host/controller.py` REAL_THREE profile can execute
these same nine synthetic-descriptor tests using its existing verified
`HOST_SOURCE_BUILT_CAPABILITY`. `matcher_supplement.py` does not convert that capability
into the older `validation.json`/`aar-input.json` format. An offline, strict Gradle task
captures the already-resolved `kotlinCompilerClasspath`; every compiler JAR is checked
against the repository's dependency verification hashes. Fresh compilation uses only the
listed production sources, this test/runner, exact verified AAR classes, and the small
explicit runtime-JAR subset of `prepared.json`—never previously compiled app classes.

Compilation (384 MiB/120 seconds) and execution (256 MiB/90 seconds) run through the
existing owned Runner, within its unchanged overall deadline. Compiler capture has a
90-second cap; there are no new downloads or native builds. Inputs and fresh output hashes
are checked across execution, and the existing runner checks actual OpenCV mappings.
`matcher-supplement.json` and its command logs are supplementary evidence, separate from
the selected real-photo JUnit result. An ordinary compiler failure or reserved JUnit-failure exit 10 records FAIL
but still permits the strict photographic invocation. Exit 10 is emitted only after the
runner verifies the exact nine-test count, post-test native mappings, and that every
JUnit failure is an AssertionError (not OOM, linkage, cancellation or other exceptions); all other
nonzero test-process exits fail closed. cancellation, deadline, signaled
processes and integrity/resource errors fail closed. Supplementary PASS never overrides
a strict real-three failure. This integration does not arm a new workflow attempt or
qualify Android/device/real-photo quality.
