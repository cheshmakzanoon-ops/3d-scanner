# Actual notification-to-native token entrypoint isolation — opt-in reproducible HOST integration

One bounded HOST test, two real NativeCore begin handles. Real Room project/job enqueue/acquire/pause/resume creates actual attempt1 and attempt2. Reflection binds only private controller ownership fields to actual job/attempt2/token; no worker, foreground service or reconstruction kernel is started. Original NativeCore class remains acquired/uninstrumented in one sandbox. The unchanged accepted core-only shim and engine are hashed before/after; actual native maps and native method modifier/class identity are checked. No Utils bridge or custom shadow is used, no OpenCV native library is loaded by this scenario.

Assertions: stale attempt1 CANCEL is rejected; other queued job CANCEL is accepted while both handles remain uncancelled; valid current attempt2 CANCEL durably cancels the correct job and only its actual native token. The other native handle is deliberately retained as an isolation sentinel after its Room attempt is paused; this is explicit fixture ownership setup, not real worker-lifecycle evidence.

## Cancellation oracle: no invented polling field

`pollNativeProgress` returns7 fields (schema, stage, reference, tile, completed, total, sequence), none is a cancellation flag. Poll assertions cover only liveness/schema. `jni.cpp` reconstructCancellable calls `withOperation`, which acquires the actual operation, establishes cancellation scope and runs checkpoint BEFORE the reconstruct argument checks. Thus `reconstructCancellable(DoubleArray(0),0.01,token)` must throw exact IllegalArgumentException("Expected 20–20000 seven-element matches") for uncancelled tokens, but actual JNI CancellationException("Reconstruction cancelled") for a cancelled token. Invalid empty input never reaches the reconstruction kernel. No tokens/exception results are fabricated, and no direct cancellation of scenario tokens occurs before observation. This proves cancellation-entrypoint behavior, not in-flight native interruption or useful geometry.

Controller startup uses actual recovery and paused-main-looper draining from the existing NotificationCommandRaceHostTest helper. Controller scope is cancelled/joined before either token is finished or database/files are removed; if stopping fails, resources are retained for the outer process watchdog. Each cleanup action is independently attempted only after stop, and distinct cleanup failures are suppressed onto the primary. No child native work is launched. JUnit120s, main wait90s, scope cleanup10s, task5min, outer600s watchdog and separate restoration bound. Pathological native blocking still relies on the process watchdog.

## Exact owner invocation requirements

Use the accepted tools/full_core_validation/core explicit config schema, exact existing core shim/opencv/engine/AAR pins and actual current compiled app classes, plus the COMPLETE reviewed447-entry native/source/consumed/header/dependency provenance closure (or owner-reviewed current equivalent). Wrapper reads paths from reviewed hash-bound configuration; no native build/download occurs. It keeps current unused fixture/OpenCV file verification from the accepted wrapper solely to minimize invocation changes; the scenario does not import photos or initialize OpenCV. No graphics/native-image qualification is implied.

```
python validate.py --config "$REVIEWED_CONFIG" --config-sha256 "$CONFIG_SHA256" \
  --expected-commit "$CLEAN_PUBLISHED_SOURCE" --output "$FRESH_EXTERNAL_EVIDENCE"
```

The temporary Gradle task selects only NotificationNativeTokenHostTest; original default gates are untouched. Runtime owner must review exact config/source hashes, source relationships and whole command before execution. Standard strict/offline SDK resolution, fresh fork, environment override rejection, unique actual class owners, pre/post inventories and normal compilation restoration remain unchanged. All available failures must be retained. The exact executable source files passed the actual external run documented below; repository packaging preserves their bytes, so no second runtime result is invented.

Not covered: concurrently swapping token ownership during a suspended dispatch transaction, service scheduling/permissions, full controller worker execution, resource admission, actual reconstruction kernel cancellation or physical-device qualification.

## Recorded actual result and portable configuration

The source snapshot actually exercised here is ae756d79a4880b735a40bfa1ac536ba64d11a5e0. Actual one test passed with zero failures/errors/skips in6.414seconds. Both stale-attempt and other-job actions preserved the two native handles; valid current action cancelled only its matching handle. Normal compilation restoration completed and all447 native provenance entries/source/runtime hashes remained unchanged. This is actual cancellation-entrypoint evidence, not a running native reconstruction kernel or Service lifecycle test.

Use the full explicit configuration schema and prerequisites documented in [full-core configuration](../full_core_validation/README.md#explicit-configuration-and-owner-invocation), selecting the **core-only** shim pin427f2c8c… (not additive publication shim). `repo`, environmentScript, java, gradle, OpenCV AAR/classes/duplicate runtime, actual appClasses, native opencv/shim/engine and nonempty provenance are all required. Each artifact has an absolute path and SHA256. Obtain original providers from the actual locked environment; do not guess cache paths. Provenance must enumerate the complete reviewed consumed/source/header/native/dependency file closure, not only a receipt JSON filename; the helper hashes listed files but does not recursively interpret their claims.

Run from any directory with a reviewed hash-bound config and fresh external output:

```
python tools/notification_native_validation/validate.py \
 --config "$CONFIG" --config-sha256 "$REVIEWED_CONFIG_SHA256" \
 --expected-commit "$PUBLISHED_COMMIT" --output "$NEW_EXTERNAL_PROOF_DIRECTORY"
```

This opt-in command does not alter default Gradle/CI gates, download dependencies or compile native libraries. Follow the existing [native source recipe](../full_core_validation/NATIVE_BUILD.md) and independent provenance review if capabilities must be rebuilt; changed binaries are not automatically trusted. No environment configuration, native binary, build cache or duplicate fixture is bundled. Raw outputs contain local paths; sanitize before sharing. Mocked helper/static tests validate wiring only and cannot replace the actual JNI receipt.
