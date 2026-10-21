# Production feature adapter — restricted HOST capability

This opt-in experiment executes unchanged production `FeatureExtractor.extractObserved`, `ImageLoader.upright`, `AndroidImageDecoder`, grayscale conversion and SIFT. It uses the current OpenCV AAR Java classes and verified official OpenCV4.12 Linux JNI. It does **not** execute `SparsePipeline`, geometry, caches, resource admission, publication or a full scanner.

## Explicit platform substitute

The Linux library does not export Android `Utils.nBitmapToMat2`; the official Android implementation uses `AndroidBitmap_lockPixels`. `OpaqueBitmapBridge` is an explicit test-only Robolectric shadow of **only** `org.opencv.android.Utils.bitmapToMat`.

It copies live **opaque ARGB_8888 only** through `Bitmap.getPixels` into real RGBA Mat bytes. It rejects nonopaque pixels, RGB565 and requested alpha unpremultiplication rather than silently changing premultiplication semantics. A literal independent2×2 byte oracle verifies channel order and alpha before any adapter test. This does **not** validate the real Android native Bitmap bridge, decoder equivalence across platforms, or device behavior.

Real OpenCV `core`, `features2d`, `imgproc`, `utils` classes and the sole loader helper remain parent-loaded; Android Utils alone remains sandbox-instrumented. The test requires matching classloader identities and exact AAR code origins, native hash/version/mappings and actual Mat/SIFT operations. No algorithm shadow, `OpenCVLoader.initLocal` override, or fabricated resource-success result is used. `NativeCore.kt` is compiled because unused `ImageLoader.quality` references it; scanner native mappings are forbidden and no scanner method is called.

## Ordered acceptance gates

One JUnit test fails closed at the first unmet prerequisite:
1. Exact real Mat byte roundtrip and SIFT native smoke; OpenCV4.12.0 and at most two threads.
2. Literal2×2 opaque RGBA byte check; unsupported alpha/config/unpremultiplication rejected.
3. Actual adapter consumes committed relief `view-01.jpg` (fixed SHA), unchanged800×600/default feature settings. Require≥20features, descriptor128float rows, complete native read byte count, nonzero/finite/range-checked values and colors matching decoded pixels at keypoints. JPEG SHA must remain unchanged.
4. Observe RGBA released before the kernel; Bitmap remains live during it and is recycled after return. Retained descriptors remain usable until frame close.
5. Request cancellation from FINISHED callback, after the real kernel returns: exact cancellation message, no returned frame, captured Bitmap/RGBA cleanup. This is **not** in-flight interruption or exhaustive native-allocation leak proof.

The independent production decoder HOST suite is a prerequisite, not replaced by the bridge test. The adapter shares its `rf.decoder.fixtureDir` convention but does not consume the separate ImageIO oracle. No Python algorithm implementation or ImageIO ingress is used here.

## Explicit existing inputs

```
python tools/feature_adapter_validation/validate.py \
 --repo "$REPO" --work "$NEW_EXTERNAL_WORK" --java "$JAVA17" \
 --compiler-cp "$KOTLIN_COMPILER_CP_FILE" --runtime-cp "$RUNTIME_CP_FILE" \
 --dependency-inventory "$OWNER_INVENTORY_JSON" \
 --dependency-inventory-sha256 "$REVIEWED_INVENTORY_SHA256" \
 --android-sdk-jar "$PINNED_ANDROID_ALL_JAR" --offline-sdk-dir "$OFFLINE_SDK_DIRECTORY" \
 --capability-dir "$OPENCV_CAPABILITY_DIRECTORY" --aar "$CURRENT_OPENCV_AAR" \
 --sift-linkage "$SIFT_LINKAGE_RECEIPT"
```

Classpath files contain ordered platform-separated canonical jar paths. The compiler closure is the existing Kotlin2.1.21 compiler used by the other native feature gates. The runtime inventory must be exported/reviewed from the actual current `:app:hostIntegrationTest` task, **not discovered by guessing cache versions**. Its schema is:

```
{"schemaVersion":1,"task":":app:hostIntegrationTest",
 "runtimeJars":[{"path":"<canonical dependency jar>","sha256":"<digest>"}],
 "omitted":[{"path":"<excluded task classpath entry>","reason":"<explicit reason>"}]}
```

Preserve task ordering. Explicitly omit all application/generated/test output directories/jars and both OpenCV providers from the exported task classpath; retain an omission record and the raw export as provenance. The helper rejects directories, app bytecode, OpenCV bytecode and dependency-resolver override resources in runtime jars, then supplies freshly compiled application sources and exactly extracted current AAR classes. Hashes bind inventory and ordering; **the helper cannot independently prove that a hand-authored inventory was exported from Gradle**—owner review/raw-export comparison is required. It never invokes Gradle.

The only offline SDK directory entry must be `android-all-15-robolectric-12650502.jar`, SHA256 `6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b`, identical to the explicit SDK jar. Existing Robolectric4.14.1/native graphics dependencies must already be available. The process-only `--add-opens=java.base/java.io=ALL-UNNAMED` supports the verified decoder environment; no persistent JVM/system setting is changed.

OpenCV inputs follow `../feature_matcher_validation/README.md` and `../feature_extractor_validation/verify_linkage.py`: pinned official4.12 build receipt, current AAR hash, exact linkage receipts and every referenced artifact must verify. No new native build, download, install, fallback dependency or version substitution occurs.

Fresh external work directory required. Compile120seconds/384MiB; run180seconds/1GiB; two JVM processors/OpenCV threads. Hash checks bind source, compiler/runtime jars, SDK, provenance artifacts and class output before/after execution. Failure retains logs/observations; only exit0 with exactly one test and successful postchecks produces a success receipt. Prior proof directories are never overwritten.

Focused Python guards (`pytest tests/test_feature_adapter_validation_helper.py`) mock compiler/JUnit execution; they are helper-integrity evidence, not native/Android coverage.
