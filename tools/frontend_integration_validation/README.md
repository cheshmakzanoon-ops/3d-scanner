# Production frontend integration — HOST only

Two frontend tests connect **current production** grayscale SIFT core, FeatureFrame ownership, reciprocal chunked FeatureMatcher and FeatureMatcher.verify to actual scanner pose/triangulation JNI. No correspondences or native results are injected. Source compiles afresh, not against cached app classes.

## Explicit ingress and fixture boundary

Use committed `benchmarks/fixtures/relief/view-01.jpg`, `view-02.jpg` and `calibration.json` with fixed hashes. `tools/make_fixture.py` documents their procedural nonplanar scene and cameras; it is hashed as provenance but **not executed**. No new download or image generation occurs. JDK ImageIO supplies sRGB ARGB pixels; the test explicitly packs RGBA then calls actual OpenCV COLOR_RGBA2GRAY. No resize, crop, EXIF transform or Android decoder is involved. The same-coordinate ImageIO sampler supplies colors to production extraction. This host adapter is fixture ingress, not a replacement claimed equivalent to production AndroidImageDecoder/Bitmap.

## Fixed initial criteria

- Positive: production settings3500 features/1600 edge, matcher block17, calibrated threshold1.5/760. Require at least20 tentative matches and20 accepted points, sparse/arbitrary-scale/two-camera schema, consistent unique accepted source indices and colors, finite coordinates and positive depth in both cameras. Rotation must be orthonormal/proper; compare with known relative pose: rotation error<2degrees and normalized translation-direction dot>0.98. Median normalized reprojection error must be within1.5/760. Camera0 is explicitly asserted identity/zero so camera1's R,t are the relative truth. No metric translation-length or surface-quality assertion.
- Negative: independently extract the same JPEG under two photo IDs; require at least20 tentative matches, then exact native-origin rejection `Rotation-only or unobservable baseline`. This rules out the Kotlin insufficient-match shortcut and rejects a visually matchable but unobservable baseline.

The pose criteria are intentionally tolerant of feature localization, JPEG decode differences and robust estimation:2degrees rotation and~11.5degrees translation direction. They were selected before execution and must not be silently relaxed after a failure. The pixel-derived normalized threshold matches the existing Android matcher test. Run output reports actual feature/match/point counts and pose/error metrics rather than asserting a historical Python result's exact counts.

## Sparse PLY interoperability

The positive case also packs the accepted points into six-column XYZ/RGB rows using **explicit test-side adaptation** of SparsePipeline's inline conversion, then invokes actual `NativeCore.exportPointCloud(...SPARSE_POINTS)` and production `LegacyPlyValidation`. It verifies the exact typed sparse header, vertex count, coordinates within the writer's nine-significant-digit precision and exact RGB values. The emitted file remains in the external work directory; output reports its SHA-256. This covers native writer/validator compatibility, not SparsePipeline's packing execution, repository option enumeration, publication, or SAF transfer.

Four additional ordinary JVM tests cover exact current sparse metadata, retained legacy sparse support, wrong/missing/conflicting metadata, bounded strict bodies/properties, and retained surface validation. Five further malformed-byte tests require exact element count prefixes and float-representable coordinates/normals while retaining decimal rounding, underflow and finite Float boundaries. Eleven tests run in total. No general PLY parser support is added.

## Reproduction

```
python validate.py compile --repo "$REPO" --work "$WORK" --java "$JAVA" \
 --compiler-classpath "$COMPILER_CP_FILE" --runtime-classpath "$RUNTIME_CP_FILE" \
 --aar "$OPENCV_AAR" --capability-dir "$OPENCV_CAPABILITY" --linkage-receipt "$SIFT_LINKAGE" \
 --native-dir "$SCANNER_NATIVE_DIR" --shim "$SCANNER_JNI_SHIM" \
 --scanner-manifest "$SCANNER_MANIFEST" --scanner-history "$SCANNER_HISTORY"
```

Repeat with `run`. Use the same pinned compiler/runtime jars as the matcher gate; see `../feature_matcher_validation/README.md` and its official OpenCV build recipe. Obtain additional SIFT signature evidence using `../feature_extractor_validation/verify_linkage.py`. The scanner manifest must bind engine/shim hashes and full observed native-source closure, with a matching recorded historical receipt; observed hashes and historical evidence are not a new native-build attestation.

Explicit arguments supply existing artifacts only. No native rebuild, install, Gradle, downloads or emulator. Compile120s/384MiB; run90s/256MiB; two JVM processors and OpenCV threads. Exact native mappings are checked before and after tests. All fixtures, production/test/helper sources, AAR/native provenance, jars and class outputs are checked before/after; successful receipts are invalidated before reruns. Existing independent proofs are not overwritten.

This validates a synthetic two-view frontend integration, **not** Android decoding, full SparsePipeline/controller, dense mesh/texturing, physical-object quality, metric scale or device behavior. FeatureMatcher.verify uses its production noncancellable native call; no asynchronous/in-flight cancellation claim. No artifact publication path is exercised.
