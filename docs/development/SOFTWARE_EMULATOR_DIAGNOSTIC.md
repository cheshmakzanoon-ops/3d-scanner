# Focused software-emulator experiment — not candidate qualification

This **manual-only, supplementary diagnostic** tests four synthetic Android
x86_64 ART/Bionic/JNI/filesystem contracts. CPU software emulation (TCG) is
explicitly unsupported by current Android Emulator documentation and can be very
slow. A successful experiment is useful runtime evidence; it does **not** replace
the accelerated device gate, the full compatibility suite, physical ARM64 tests,
camera/graphics testing, 16 KiB qualification, or performance/thermal evidence.

The existing workflows, compatibility suite scopes, required selectors and
candidate-evidence aggregation are unchanged. In particular, this diagnostic's
result must never be substituted for `verify.yml`'s `device` job.

## Launch policy

`.github/workflows/software-emulator-diagnostic.yml` has only
`workflow_dispatch`. It selects the standard `ubuntu-24.04` GitHub-hosted runner
and refuses private repositories. Standard runners are free for public
repositories. No larger/self-hosted runner, artifact upload, cache action or
external service is used. APKs, build caches and AVD files exist only on the
ephemeral job machine; only bounded normal job logs persist.

Android preferences, AVD state, Gradle state, ADB keys and the generated debug
signing key are confined to the controller's temporary directory. `HOME`, Android
user/emulator/AVD homes and Java `user.home` all point there; inherited ADB key and
device selectors are removed. No key or signing material is printed or uploaded.

No dispatch is part of implementing or reviewing these files. The proposed first
execution should be separately reviewed. Source implementation and host tests
alone must be recorded as **Android runtime NOT_RUN**.

The controller requires a preinstalled SDK, SDK command-line tools and JDK 17.
Missing required SDK package versions may be downloaded by the official existing
`sdkmanager`, stable channel only, with stdin closed. Existing SDK license-record
hashes are captured before downloads and checked afterward and after compilation.
Missing licenses, requests for new terms or license-record mutations stop with
`BLOCKED`; no license-acceptance command or affirmative response exists. AVD
creation receives `no` solely for the custom-hardware-profile question.

No `setup-android` action (which might accept terms), permission changes, group
changes, KVM configuration, SELinux changes, root access, Cuttlefish or credentials
are involved. The emulator is explicitly launched with `-accel off`, process-local
`ANDROID_I_WANT_MY_TCG=yes`, `-gpu software`, no window/snapshots/cameras, 2 GiB RAM,
and two virtual cores. An older installed emulator that cannot honor these flags
fails rather than silently changing mode or downgrading.

## Explicit budgets

| Boundary | Maximum |
| --- | --- |
| Entire GitHub job, including checkout | 75 minutes |
| Controller working deadline, including setup/build | 72 minutes |
| One SDK installation attempt; no automatic retry | 5 minutes |
| Exact-source debug app + instrumentation APK build | 20 minutes |
| Cold boot | 30 minutes |
| Selected instrumentation invocation | 15 minutes |

Every subprocess deadline is clipped by the remaining controller budget. A later
phase may therefore receive less than its individual maximum. Regular commands
have a 60-second maximum unless explicitly specified; APK installs have 120 seconds.
No timeout is interpreted as success or justification to relax a required gate.
The 72-minute controller working deadline also has a signal-based watchdog and
leaves space inside the hard 75-minute job limit for checkout and owned-process
cleanup. Forced runner termination might prevent a final receipt;
absence of a final successful receipt is never a pass.

Command/emulator output is capped at 4 MiB, instrumentation proof at 128 KiB, and
normal diagnostic tails at 16 KiB. Exceeding a cap fails closed. Subprocess cleanup
signals only process groups created and still owned by the controller, including
its one emulator and foreground private ADB server. It never uses broad
process-name killing, global `adb kill-server`, or resets other devices. Signal
handlers remain active during cleanup: cancellation or an expired deadline
invalidates a pending successful result before evidence emission.

## Private ADB and emulator ownership

The controller launches a foreground `adb server nodaemon` on its own random
loopback port, not the shared/default port. Local emulator auto-scanning and mDNS
auto-connect are disabled; a random unmatched USB selector prevents use of USB
devices. Every client command uses explicit `-H 127.0.0.1 -P <private-port>`.
The numeric host is intentional: reviewed AOSP client source treats it as a
non-auto-start endpoint, so a dead private server must not spawn a replacement
daemon outside cleanup ownership.

Before using a server, read-only `/proc` socket-inode checks require the owned
foreground process to hold its loopback listener. The emulator's two fixed ports
must first be unused; before selecting its device, its own PID must hold the
loopback ADB listener. Reviewed AOSP launcher source executes QEMU in the same PID.
Listener or process-ownership uncertainty fails closed instead of contacting an
unowned server/device. These checks do not require new permissions or security
changes.

CLI syntax and these behaviors have been checked against official help/source,
not against a running installed ADB or emulator. **Installed-tool behavior and
actual Android runtime remain unverified / NOT_RUN until a reviewed experiment.**
A tool version that differs from the source assumptions can conservatively block
startup; this diagnostic does not fall back to a shared server or relaxed checks.

## Exactly four tests

All methods in `agents.a037.CalibratedInitializerNativeTest`:

- `calibratedPlaneReportsModelAndSupportWithoutChangingLegacyContract`
- `calibratedJniRejectsMalformedDomainShapeAndMisalignedPixels`
- `cancelledCalibratedCallHonorsScopedOperationLease`

And `agents.a033.FeatureCacheDeviceTest`:

- `privateAndroidCacheHitsCorruptionRecomputesAndEvictionPreservesUnownedFiles`

Each selector is fully qualified under `com.realityforge.scanner` in the
controller. No camera/Compose/viewer activity is launched by these tests. Fixtures
remain synthetic; no reconstruction-quality or complete-object claim follows.

## Fail-closed evidence

The controller requires a clean checkout of the exact dispatched SHA and validates
the checked-in complete-source manifest before and after the build/tests. Existing
APK outputs are refused. Gradle uses strict dependency verification, no build cache,
the checked-in wrapper and existing SDK packages (automatic SDK download disabled).
Both APK manifests must identify the correct debug app and AndroidJUnitRunner
target. The x86_64 native-library hash is recorded.

The installed emulator must report API35, x86_64, emulator identity and 4096-byte
pages. App/test APK hashes are verified against the actual installed base APKs
before and after tests; split packages and malformed identities are rejected.
Source, APKs, installed APKs, SDK metadata, emulator/adb binaries and system-image
files are SHA-256 bound in the receipt. SDK/image revisions are recorded; this is a
recorded-version experiment, not a claim that the mutable runner image is pinned.

`adb` exit code is insufficient. The raw AndroidJUnitRunner output must contain:

- Each of the four exact method identities, once, with one matching start and
  successful terminal event, consistent totals and sequential indices.
- No failure, ignored test, assumption failure, duplicate, missing event, crash
  marker, incomplete output, unexpected selector or extra test.
- Exactly one `OK (4 tests)` summary and one final instrumentation code `-1`.

Only after all checks and cleanup succeed are `RF_DIAGNOSTIC_JUNIT` (one XML line)
and `RF_DIAGNOSTIC_RECEIPT` (one JSON line) emitted. JUnit is generated from the
validated real raw events, with source/APK/transcript hashes. A human-readable,
normalized display uses the `RF_DIAGNOSTIC_LOG instrumentation-full | ` prefix;
that display alone does not preserve original line endings or control bytes.
Exact UTF-8 transcript bytes (at most 128 KiB) are also emitted as bounded ASCII
base64 chunks: one `RF_INSTRUMENTATION_RAW_META` JSON line followed by numbered
`RF_INSTRUMENTATION_RAW_CHUNK` lines. Require each index exactly once in order,
strictly decode all chunks, and verify byte length and SHA-256 against both the
metadata and `testProof.rawSha256` in the final receipt. Re-run the published
parser on those bytes when reviewing evidence. Missing or truncated chunks do
not establish byte-verifiable proof. No raw control bytes become workflow commands.
Other child output is prefixed to prevent GitHub workflow-command interpretation.
Retrieve normal job logs, preserve the receipt and XML lines verbatim, and bind
them to the workflow run URL/attempt. Require both the final receipt's `PASS` and
the job's successful conclusion; a cancelled or terminated job cannot qualify
merely because partial output contains a success line. No uploaded artifact is
required or produced.

`PASS` always retains `NOT_RUN` for all original/physical/performance qualification
fields. `BLOCKED`, `TIMEOUT`, `CANCELLED`, `FAIL`, skipped jobs or missing receipts
do not establish the four-test result. Host unit tests exercise parser/policy and
process control only; they are not Android execution evidence.

## Reviewed official references

- [Android Emulator command-line options](https://developer.android.com/studio/run/emulator-commandline)
  document unsupported TCG and its explicit opt-in, headless mode and RAM settings.
- [Graphics versus VM acceleration](https://developer.android.com/studio/run/emulator-acceleration)
  documents current software graphics modes; these do not replace CPU acceleration.
- [Android command-line tests](https://developer.android.com/studio/test/command-line)
  documents class/method selection with `am instrument -e class`.
- [SDK manager](https://developer.android.com/tools/sdkmanager) and
  [AVD manager](https://developer.android.com/tools/avdmanager) document package/AVD
  command syntax. These existing runner tools are used without installing a new CLI.
- [Android environment variables](https://developer.android.com/tools/variables)
  document task-local Android user/emulator/AVD directories.
- [AOSP ADB help](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md)
  documents server sockets, host/port selection and the foreground USB filter.
  [Socket classification](https://android.googlesource.com/platform/packages/modules/adb/+/f4965b77c694689c08855076eaf983c8e88646f9/socket_spec.cpp)
  and [client startup](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-14.0.0_r27/client/adb_client.cpp)
  explain the explicit numeric-host non-auto-start behavior.
- [AOSP local transport scanning](https://android.googlesource.com/platform/system/core/+/c08fd35976a3ab841c07694503a40e5487c75d74/adb/transport_local.cpp)
  accepts zero as a maximum scan port, disabling the local port scan;
  [mDNS policy](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/android16-s2-release/adb_mdns.cpp)
  disables auto-connect when its environment switch is zero.
- [AOSP emulator launcher](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/emulator/main-emulator.cpp)
  executes the QEMU process in place, supporting the conservative same-PID
  listener-ownership check. Actual installed-tool behavior must still be verified.
- [Ubuntu24.04 runner image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)
  lists the preinstalled SDK/JDK. Actual prerequisites are rechecked at runtime.
- [Standard public-runner availability and costs](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
  documents standard public-repository runners as free.

Before integration, regenerate `SOURCE_MANIFEST.sha256` using the repository's
mechanical manifest protocol. Do not weaken source verification to accommodate
uncommitted or unmanifested diagnostic files.
