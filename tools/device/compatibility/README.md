# Installed candidate scopes and matrix

Build candidate APKs first. Debug compatibility: `bash tools/run-device-tests.sh --scope debug --serial SERIAL --app-apk APP.apk --test-apk TEST.apk --expect-api 36 --expect-abi arm64-v8a --expect-page-size 16384 --output reports/device/SERIAL`. Release compatibility: use `--scope release` with the minified non-debuggable app APK and matching release test APK. The default scope is debug; CI should select it explicitly. This runner never builds when arguments are supplied.

The checked-in `suites.json` is the versioned selection contract. AndroidJUnitRunner receives one `-e notClass` argument containing its exact class/method exclusions. All other annotated tests remain discoverable: native/JNI/OpenCV, ordinary repository/recovery/provider/privacy checks, GPU shader/model loading, and real synthetic import/reconstruction/export/reopen tests. The runner requires actual passing SessionReliability full-session and Compatibility native/page-size methods in both scopes. Release also requires the actual ReleaseNativeContractTest method to pass; a missing release contract or a skipped required method fails.

| Required specialist gate | Debug exclusion | Release exclusion | Separate qualification |
|---|---|---|---|
| physical_capture | PhysicalCaptureContractTest class | Same class |086 authorized physical project/camera/capture protocol |
| external_recovery | RecoveryStageContractTest#externalProcessBoundary | Same method |088 nonce-bound external kill/relaunch/upgrade controller |
| physical_resource_profile | ResourceProbeContractTest#profileOperatorSelectedDisposableProject | Entire debug-only ResourceProbeContractTest class |087 authorized workload, supported counters and physical conditions |
| offline_radios_journey | OfflinePrivacyJourneyTest#explicitOfflineSyntheticImportReconstructionInspectionReopenExportJourney | Same method |089 controller independently verifies disabled radios |
| minified_release | ReleaseNativeContractTest class | Included and required to pass |091 exact minified app/test candidate; other release gates still apply |

All selectors above use the fully qualified names in the manifest; resource probe tests live in `com.realityforge.scanner.agents.a087`, the other specialist classes in `com.realityforge.scanner.qa`. Debug keeps the ordinary bounded debug probe counter test. Offline privacy permission/storage cases and bounded fault-provider tests remain enabled in both scopes. No arbitrary positive class selector can hide ordinary tests. The runner rejects any excluded test appearing in its raw transcript, including an assumption/ignored event, and preserves strict actual pass/skip/failure counting.

`compatibility.json` includes `suite={schema:1,scope,manifestSha256,notClass,requiredPassedSelectors,excludedGates,fullQualification:"NOT_RUN"}`. Every excluded gate has `{gate,selector,status:"NOT_RUN",reason}`. These exclusions are separate from tests that actually execute and skip; they do not increase passed counts. A compatibility PASS, screenshot, or runtime16kb PASS cannot promote an excluded specialist gate or full qualification.099 must require those gate receipts separately. On failures after scope loading the receipt still names the exclusions and reasons.

A successful scope also requires fresh decoded full-session PNG pixels, complete raw instrumentation events, nonzero actual passes, candidate package hash matches and the expected environment. Failed install/test/pull/cleanup exits nonzero. Device fingerprint, serial, API, ABI and measured page size accompany exact APK SHA-256s. Devices are never uninstalled by this runner; use disposable test installations.

Required release environments: API29 x86_64/4KB (minimum), API35 x86_64/4KB (intermediate), API36 x86_64/4KB (target), API35+ arm64-v8a/16KB, and physical ARM64 camera/GPU qualification. API35 16KB ARM64 emulator image: `system-images;android-35;google_apis_ps16k;arm64-v8a`, on a compatible ARM64 host. Verify `adb -s SERIAL shell getconf PAGESIZE` returns16384. Missing image/host is BLOCKED_EXTERNAL; ELF/ZIP alignment cannot establish runtime support. Candidate splits require installation with `adb -s SERIAL install-multiple -r ...` and hashes of all splits plus equivalent package tests; this single-base-APK runner alone does not certify AAB/splits.

An emulator PASS describes its selected compatibility scope only. Physical capture remains NOT_RUN in this receipt and is qualified by086. Missing required rows or specialist gates block release acceptance.
