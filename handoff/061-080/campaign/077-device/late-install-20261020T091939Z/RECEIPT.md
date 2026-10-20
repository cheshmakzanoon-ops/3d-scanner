# Agent077 late-installed app runtime follow-up

**Actual result: 3 tests run, 0 passed, 3 failed.** Automated accessibility gate: **FAIL**, with failure diagnosis pending. This is a separate follow-up to the preserved [earlier installation timeout](../attempt-20261020T090718Z/RECEIPT.md).

The installed app was verified from its actual `pm path` base APK. Device SHA256 `5830c15c4fcd7675dcb03b8414c6937f6671550fdc22225966057cd541d5eb02` matches the exact Agent077 app APK. The paired test APK SHA256 is `22bd5f6a68b06ca1e79d82d5c611b3d87821f4f9bf6ed537c71d42b31a6a82a2` (2239070 bytes). Build source: `0c76ec2d2f9ab4dd3c7480036e9566b84307a515`. Only the test APK was installed in this attempt; its no-streaming install succeeded in 8.310 seconds. No source edits.

Device: software emulator `emulator-5556`, API 29, ABI `x86_64`, fingerprint `Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys`. Boot completed; system server PID 4585 remained unchanged. Physical hardware and human TalkBack review remain **NOT_RUN**.

The single instrumentation invocation completed in 154.481 seconds, before its 360-second deadline. The AndroidJUnitRunner output records 124.590 seconds of test time and `Tests run: 3, Failures: 3`. ADB exit code was 0; the JUnit result still failed.

| Test | Result | Reached failure |
| --- | --- | --- |
| `primaryActionRemainsReachableAtTwoHundredPercentText` | FAIL | Initial visible library-control await, source line 81 |
| `visiblePrimaryLibraryActionHasTouchTargetAndClickSemantics` | FAIL | Initial visible library-control await, source line 81 |
| `realCreateDialogHasNamedEditableFieldAndReturnsToCorrectProject` | FAIL | Initial visible library-control await, source line 49 |

All failures report `Expected actual accessibility control did not become available`. No test reached its touch-target, editable-field or recreation assertion. The expected `Create local project` text exists in the app source. The captured guest system log shows a pre-existing `Application Not Responding: com.android.systemui` window during launches, launch/window timeouts and delayed UI dispatch. The captured crash buffer contains no new app crash. These observations do not establish whether the missing active-window control was caused by the app or the guest UI.

`screen.png` was captured after instrumentation teardown and shows a blank activity. It is preserved as a post-run screenshot, not represented as a screenshot of an assertion failure. A useful receiving-owner diagnostic is to capture the active accessibility-window package/tree and screenshot inside the failed await before activity teardown. No assertion was weakened, and no second instrumentation invocation occurred.

Exact command/timing records: `commands.json`. Installed identity: `installed-app-path.log`, `installed-app-sha256.log`. Real JUnit stdout: `actual-accessibility.log`; derived XML: `junit.xml`; individual failures: `failure-details.json`. Before/after guest logs, the screenshot and the final ADB-client snapshot accompany the receipt. Machine-readable outcome: `result.json`; hashes: `SHA256SUMS`.

The campaign device lock covered the complete attempt and is released. Final container-client inspection shows only the shared ADB server; no install or instrumentation client remains.
