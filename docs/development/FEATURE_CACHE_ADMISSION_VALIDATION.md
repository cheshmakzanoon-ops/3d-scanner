# Bounded feature-cache constructor admission — 23 October 2026

## Reproduced defect and correction

On baseline `2c47989d905e5a205a4bd814f5fa2511035ca956`, `CachedFeatures`
copied all caller arrays before checking image dimensions, feature count, and
array shapes. Each synthetic malformed 8,000,000-element coordinate, color, or
descriptor array fits within the test JVM's 48 MiB heap; its defensive copy does
not. Each case raised `OutOfMemoryError` in `Arrays.copyOf`, rather than the
contract's `IllegalArgumentException`. This is a caller-array admission defect,
not evidence that the bounded on-disk decoder accepts an oversized file.

The constructor now checks dimensions and immutable array lengths first. The
20,000-count bound precedes `count * 2` and `count * 128`, so multiplication cannot
overflow. Value-domain validation still reads the defensive copies, not mutable
caller arrays. Accepted geometry, descriptors, array ownership, wire format and
cache identities are unchanged; no cache version bump is appropriate.

## Actual host evidence

Kotlin 2.1.21 targeting JVM 17, OpenJDK 21.0.12.1, JUnit 4.13.2 and
Hamcrest Core 1.3; actual production `FeatureCacheFormat.kt` and `FeatureCache.kt`
compiled directly, without Android or OpenCV substitutes.

- Before fix: **15 tests, 3 failures**, all three expected rejection tests instead
  receiving `OutOfMemoryError` from the production constructor's defensive copy.
- After fix: **15 tests passed** at `-Xmx48m`, including all 10 existing cache
  tests and 5 new admission/ownership tests.
- Source hashes before and after each compilation/execution were identical.
- A separate nonempty endpoint fixture encoded against baseline and candidate
  classes produced byte-identical cache files (SHA-256
  `bf0e90357a221f0099c89169c30931968c8c008292a5eddbd379901dbff46271`).
  Candidate classes decoded the baseline bytes with exact coordinate/color/
  descriptor equality. This is a fixed compatibility fixture, not every input.
- Source-manifest and project-contract pytest checks: **63 passed**. Full pytest
  was not run for this isolated Kotlin-only delta; live dependency audits were
  not invoked.
- Existing suite covers exact round trips, truncation, checksums, invalid numeric
  values, mutation leases, atomic writes, eviction, symlink and size rejection.
- New tests also exercise maximum accepted count/dimensions, rejected shapes,
  and both input/output defensive-copy immutability.

Reproduce from the repository root with local official Kotlin/JUnit dependencies:

```sh
# Set KOTLINC, JAVA, STDLIB, JUNIT and HAMCREST to local executable/JAR paths.
# Set OUT to a fresh output directory outside the repository.
mkdir -p "$OUT"
CP="$STDLIB:$JUNIT:$HAMCREST"
"$KOTLINC" -jvm-target 17 -classpath "$CP" -d "$OUT/classes" \
  app/src/main/java/com/realityforge/scanner/engine/features/FeatureCacheFormat.kt \
  app/src/main/java/com/realityforge/scanner/engine/features/FeatureCache.kt \
  app/src/test/java/com/realityforge/scanner/engine/features/FeatureCacheTest.kt \
  app/src/test/java/com/realityforge/scanner/engine/features/FeatureCacheAdmissionTest.kt
"$JAVA" -Xmx48m -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore \
  com.realityforge.scanner.engine.features.FeatureCacheAdmissionTest \
  com.realityforge.scanner.engine.features.FeatureCacheTest
```

`tests/test_feature_cache_admission_host.py` automates this exact bounded-heap
compilation/test sequence in pytest. Configure `RF_KOTLIN_HOME`, `RF_JUNIT_JAR`
and `RF_HAMCREST_JAR` (optionally `RF_JAVA`), or provide Kotlin/Java on PATH and
pinned JUnit/Hamcrest in the Gradle cache. It downloads nothing. Missing tools
produce an explicit `NOT_RUN` skip; configured invalid tools and compilation/test
failures fail. It strips injected JVM heap options, uses fresh outputs and bounded
compile/run timeouts, and checks source/runtime-JAR hashes remain stable.
The actual controller passed on the candidate and failed on isolated baseline
production sources with the same three constructor-copy `OutOfMemoryError`s.

The explicit heap bound is essential to the allocation-order regression: with a
large heap, the old constructor eventually rejects the arrays too. This bounded
run is a constructor resource-admission check, not Android memory qualification.
No native code changed. Android Gradle compilation/lint/instrumentation and device
resource/cancellation behavior were **NOT_RUN**; no Android runtime was available.

## Separate unexecuted extraction finding

The Android adapter checks cancellation before Bitmap-to-Mat/grayscale conversion,
while the shared SIFT core checks it only after `detectAndCompute`. Cancellation
during conversion may therefore still start the noninterruptible kernel. This is
source inspection only: no real OpenCV Java JNI capability/AAR was available in
this workspace, and pytest fixture binaries were not used as substitutes.
No extraction/cancellation code is changed in this patch. Any future correction
must preserve cleanup/observer semantics and must not claim it can interrupt an
already-started SIFT call. Tiny-image admission is also left unchanged.
