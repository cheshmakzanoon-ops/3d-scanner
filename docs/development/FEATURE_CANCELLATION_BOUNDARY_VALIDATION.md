# Feature cancellation at the grayscale boundary — 23 October 2026

## Reproduced production behavior

`FeatureExtractor` checks cancellation before Android bitmap conversion, then
calls the shared `extractGrayFeatures` core. Previously that core first polled
cancellation **after** the noninterruptible SIFT `detectAndCompute` call. A
request already pending when grayscale conversion finished still started SIFT.

A new deterministic synthetic-grayscale host test calls the actual production
core with cancellation already requested. It expects a `CancellationException`,
no native-call events, no color sampling, unchanged borrowed pixels, and successful
subsequent uncancelled extraction. With unchanged production code, the actual
OpenCV JNI suite ran **11 tests, 1 failure** at the no-native-call assertion; all
10 prior tests passed. The baseline failure log and compiled classes are retained.

The fix adds a cancellation check after grayscale admission and before allocating
SIFT resources. The candidate ran **11 tests, 0 failures** against real OpenCV
4.12.0 Java/native code. Existing tests preserve cancellation requested in STARTED
or FINISHED, row-cadence/final publication checks, borrowed/owned resource behavior,
observer exceptions and real native failure suppression. This does **not** add
interruption of a kernel already started or change STARTED/FINISHED semantics.
The synthetic test represents cancellation pending at the conversion boundary;
it does not execute Android bitmap conversion or measure an asynchronous device
cancellation latency.

Successful feature pixels/order/descriptors/settings identities are unchanged,
including direct-SIFT parity and the existing exact settings-identity fixture.
No cache or extractor identity bump is needed for this cancellation-only change.
Tiny-image admission remains unchanged.

## Actual official host capability

The initial existing host JDK lacked AWT/JNI discovery prerequisites; configure
correctly reported no Java wrappers. No fake headers, libraries, or FindJNI success
flags were substituted. An official Temurin **17.0.21+9** archive was downloaded
through Adoptium's API, verified against its published checksum, and extracted
only in the cloud workspace. No system installation, Android SDK license,
security setting, permission change, or new cloud environment was used.

The official OpenCV **4.12.0** source at commit
`49486f61fb25722cbcf586b7f4320921d46fb38e` matched the already documented archive
SHA-256 `8f00b42869ab2836be36090f6631ae4c38ba59171e22a69a8e0f92f8ef1771d4`.
The build followed `tools/feature_matcher_validation/OPENCV_BUILD.md`: only
core/features2d/imgproc/java, Java wrappers YES (JAVA), fat JNI library, SSE3
baseline, IPP/OpenCL disabled, two build workers, fail-closed dependency-download
hook, 2700-second cap, and monitored 2 GiB disk floor. It completed all 205 targets
in **250.01 seconds**. 7,612 original source-file contents matched the archive;
only generated `.cache/.gitignore` and Python parser bytecode were added.

The Java runtime used `classes.jar` from the official Maven Central **4.12.0 AAR**,
not generated Linux classes. Native declarations matched the generated JAR:
**361** base declarations plus **37** SIFT/Feature2D/MatOfKeyPoint declarations,
with exported JNI symbols checked (including descriptor-mangled overloads).
Actual Mat round-trip/version smoke and `/proc/self/maps` checks confirmed the
expected library alone. ELF dependencies were resolved standard host libraries
(zlib, libm, libstdc++, libgcc, libc and loader), with no second OpenCV library.

Artifact SHA-256 identities:

```text
f71846a313388d9da667a59be1e921669fcf792d1ba264b3898253ca789bc3f0  opencv-4.12.0.aar
fbec66c3385340ac64793e3d9024e0d874166108045c2fbd75d6ff0f7ce7ce84  libopencv_java4120.so
8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711  temurin17-jdk.tar.gz
27b8caf80426bb967fb0624c5469741cd695a62e8b4e9dfe310fc8ee6e434b97  base capability validation.json
ce86085cd8b2dc5f27bdbd4cc812766bc06a05fea73b0af6bc133293b07320d5  sift-linkage.json
836870109f500a046608899c43dd523e3946bb2c31d60ca8ce19a838cf0ebe79  candidate run-receipt.json
```

## Reproduction and limits

Workspace evidence is retained under
`/workspace/shared/opencv-java-capability-audit` (downloads/licenses, original
source manifest, failed and successful configure logs, build/disk observations,
linkage/smoke evidence) and `/workspace/shared/feature-cancellation-evidence`
(baseline failure, candidate success, exact source/toolchain/output-bound receipts).
`run.sh before` was executed before modifying production; `run.sh after` compiled
and executed the fixed production source. The helper intentionally rejects stale
compile receipts rather than treating the old baseline classes as current source.
Use a fresh output name to independently rerun the candidate.

For other hosts, follow the existing `tools/feature_extractor_validation/README.md`
compile/run interface and official build instructions. This patch updates the
runner and receipt expected count from 10 to 11; it does not loosen identity checks
or replace real SIFT with fixtures. Helper controller/linkage pytest tests passed
(**22 tests**) but are separate from the actual JNI execution.

This is **host JNI** evidence, not Android ABI equivalence, Bitmap/EXIF adapter
coverage, device memory/deallocation proof, physical scan quality, or complete
pipeline qualification. Android compilation/lint/instrumentation were **NOT_RUN**.
The previously unexecuted cancellation finding in
`FEATURE_CACHE_ADMISSION_VALIDATION.md` is superseded only for this shared host
core boundary; that document's Android limitations still apply.
