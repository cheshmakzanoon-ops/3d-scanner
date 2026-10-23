# Four focused Android ATD runtime checks — supplementary non-UI evidence

**Result: four tests passed, zero failures or skips.** This is not release or full-device qualification.

- [GitHub Actions run 39967166172, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39967166172)
- Exact source: `ba259b96d3fdcba8c304a8214764d0d70e51be80`.
- Job `120459938157` (`focused-software-runtime`): completed **success**, independently checked through GitHub's jobs API on 2026-10-23 at approximately 14:26 UTC. All listed steps, including post-checkout and Complete job, succeeded.
- Controller: 2026-10-23 14:09:06–14:22:30 UTC; 804.219 seconds, within its unchanged 4,320-second deadline and 75-minute job limit.

## What ran

Official API35 AOSP ATD revision 1, x86_64, 4,096-byte pages, unsupported CPU software emulation. Factory HWUI drawing was **disabled** (`debug.hwui.drawing_enabled=0`); ATD replaces/removes UI components. The recorded timeout multiplier was empty; debugger and watchdog override settings were `null`. Strict factory admission passed without setting writes. Complete measured values and system-image hashes remain in `receipt.json`; broad watchdog equivalence remains unverified.

Exactly these unchanged methods each emitted one start and one passing completion:

1. `CalibratedInitializerNativeTest#calibratedPlaneReportsModelAndSupportWithoutChangingLegacyContract`
2. `CalibratedInitializerNativeTest#calibratedJniRejectsMalformedDomainShapeAndMisalignedPixels`
3. `CalibratedInitializerNativeTest#cancelledCalibratedCallHonorsScopedOperationLease`
4. `FeatureCacheDeviceTest#privateAndroidCacheHitsCorruptionRecomputesAndEvictionPreservesUnownedFiles`

Their full class identities are in the receipt/transcript. They exercise synthetic JNI inputs and app-private cache behavior, not Activities or rendering. The exact 3,158-byte transcript contains eight ordered status events, `OK (4 tests)` and final `INSTRUMENTATION_CODE: -1`. Its SHA-256 is `85b97713a5d033e80c76ca866413f67a5d28c979413cf6eb9f0e53f2fc773119`.

## Identity and completion checks

| Built and installed APK | SHA-256 |
| --- | --- |
| App (144,878,589 bytes) | `18745226f21bd1851f59cc78b2a5aaea21036da6c29ed754ef9b7add07a7843c` |
| Test (5,628,801 bytes) | `eff04e4c37e13dad37f303dd5653549bf8b81c72d40f6d5c22b303e0a06d4956` |

Both hashes were checked on the device before and after instrumentation. The pinned source and its committed manifest were reverified after testing. Owned emulator/ADB cleanup log records precede final JUnit and PASS receipt; no cleanup error is present. The pinned controller makes final PASS conditional on process/temp cleanup and a final deadline check. This is controller/log evidence, not an independent post-job operating-system process census.

`verification.json` records the independent checks. The original full job log was inspected (SHA-256 `31ad199868adc9cd1f51258972b226df913ec67b3db9dd2efa554389adb8ca4b`) but is intentionally not packaged. The two ordered base64 chunks in that log reconstruct `instrumentation.raw.log` exactly; the pinned source's strict parser reproduces `receipt.json`'s `testProof`. JUnit reported 4 tests / 0 errors / 0 failures / 0 skips.

## Files and verification

- `receipt.json`: exact original extracted terminal receipt, unmodified.
- `instrumentation.raw.log`: exact reconstructed instrumentation bytes, unmodified.
- `instrumentation-meta.json`: original extraction metadata, unmodified.
- `job-result.json`: independently fetched GitHub jobs API structured result; no account/connector identifiers.
- `verification.json`: independent local verification summary, not a substitute for primary evidence.
- `SHA256SUMS`: hashes of this package's other files; verify using `sha256sum -c SHA256SUMS`.

To replay strict transcript parsing, inspect/use `parse_instrumentation()` from `tools/device/software_emulator/diagnostic.py` at the exact source commit above, and compare its output to `receipt.json`'s `testProof`. Do not reinterpret missing/skipped events as passing. GitHub job success is separate from the transcript and must also be retained.

No APK, full job log, private/public key material, credentials, or user-device logs are included. No runtime or gate code is changed by publishing this evidence.

## Explicit limitations

This is **supplementary NON-UI evidence only**. It does not establish UI/stock-phone behavior, physical scanning quality, graphics, performance, ARM64, 16 KiB compatibility, full instrumentation, or release qualification. Existing original-device/KVM and sanitizer requirements are not waived. ATD success does not diagnose the earlier stock-image startup ANRs. The failure-only DropBox capture was not exercised in this successful run. Separate CI outcomes are not represented as passing by this package.
