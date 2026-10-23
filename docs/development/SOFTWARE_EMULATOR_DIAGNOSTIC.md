# Focused software-emulator experiment — not candidate qualification

This **explicitly opted-in, supplementary diagnostic** tests four synthetic Android
x86_64 ART/Bionic/JNI/filesystem contracts. CPU software emulation (TCG) is
explicitly unsupported by current Android Emulator documentation and can be very
slow. A successful experiment is useful runtime evidence; it does **not** replace
the accelerated device gate, the full compatibility suite, physical ARM64 tests,
camera/graphics testing, 16 KiB qualification, or performance/thermal evidence.

The existing workflows, compatibility suite scopes, required selectors and
candidate-evidence aggregation are unchanged. In particular, this diagnostic's
result must never be substituted for `verify.yml`'s `device` job.

## Launch policy

`.github/workflows/software-emulator-diagnostic.yml` retains `workflow_dispatch`
and permits the single tightly bound push described below. It selects the standard `ubuntu-24.04` GitHub-hosted runner
and refuses private repositories. Standard runners are free for public
repositories. No larger/self-hosted runner, artifact upload, cache action or
external service is used. APKs, build caches and AVD files exist only on the
ephemeral job machine; only bounded normal job logs persist.

Android preferences, AVD state, Gradle state, ADB keys and the generated debug
signing key are confined to the controller's temporary directory. `HOME`, Android
user/emulator/AVD homes and Java `user.home` all point there; inherited ADB key and
device selectors are removed. No key or signing material is printed or uploaded.

Implementation and host tests do not execute Android. Publishing the exact
opted-in commit intentionally requests one automatic attempt under the existing
authorized repository CI; it is not a credential or permissions workaround.
Until actual runtime output exists, record **Android runtime NOT_RUN**.

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

### One-shot push admission

Automatic execution is limited to the public `cheshmakzanoon-ops/3d-scanner`
repository's `refs/heads/main`, on attempt 1, with all of the following:

- Previous head exactly `7932b9a0e590d7c06815e6f41d7446c23d879b9b`.
- Exact commit message `test: run software Android diagnostic with verified host libraries`.
- A normal update, not a created/deleted branch or forced push.
- Event `after`, head-commit ID and `GITHUB_SHA` all identical.
- One non-merge commit whose actual parent is that previous head; the committed
  message must also match the event's explicit opt-in.
- Only modifications to `.github/workflows/software-emulator-diagnostic.yml`,
  `tools/device/software_emulator/diagnostic.py`,
  `tests/test_software_emulator_diagnostic.py`, this document and
  `SOURCE_MANIFEST.sha256`. Both the workflow and controller must change.
  Added/deleted/renamed paths, unrelated changes and missing history are rejected.

The workflow filters pushes by the two exact workflow/controller paths and has a
job-level event guard. The controller independently checks the event, clean exact
SHA, complete committed manifest, real Git ancestry and changed paths **before
any SDK operation**. Checkout is SHA-bound with depth 2 and no persisted
credentials; the controller does not fetch history or credentials. Path filters
alone are not an authorization check: they require only one matching file, and
the Actions push payload omits per-commit changed-file lists.

The fixed prior SHA makes ordinary subsequent commits ineligible; a reusable
message tag alone would not. Automatic reruns are blocked by the attempt check.
This is a forward-history guard, not a durable lock against an owner deliberately
resetting the branch and replaying a previously authorized update.
Any later automatic attempt requires a separately reviewed source change that
explicitly re-arms the policy. The existing manual path remains available and
separate: this does not promise global deduplication against a user requesting a
manual run. The unchanged concurrency group serializes manual/push work.

A main push also runs the repository's existing normal verification, sanitizer
and source-snapshot workflows, including their existing artifact/cache behavior.
The no-upload rule here is diagnostic-specific. Those workflows and all original
qualification gates are neither suppressed nor altered. The legacy compatibility
workflow remains PR/manual-only; this diagnostic never dispatches it. Do not use
skip-CI commit markers, which would suppress required ordinary push checks too.

## Explicit budgets

| Boundary | Maximum |
| --- | --- |
| Entire GitHub job, including checkout | 75 minutes |
| Controller working deadline, including setup/build | 72 minutes |
| One SDK installation attempt; no automatic retry | 5 minutes |
| Headless ELF check / optional private host-library preparation | 5 minutes |
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

Text command/emulator output is capped at 4 MiB, instrumentation proof at 128 KiB, and
normal diagnostic tails at 16 KiB. Exceeding a cap fails closed. Subprocess cleanup
signals only process groups created and still owned by the controller, including
its one emulator and foreground private ADB server. It never uses broad
process-name killing, global `adb kill-server`, or resets other devices. Signal
handlers remain active during cleanup: cancellation or an expired deadline
invalidates a pending successful result before evidence emission.

## Headless host-library preflight and narrowly scoped repair

The first actual automatic attempt, [run 39929260041, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39929260041),
at source `b96f1568539f1aa20792f6e1c3d06d0bda93bd92`, failed during prerequisites:
plain `emulator -version` selected `qemu-system-x86_64`, whose loader could not
find `libpulse.so.0` (exit 127). Event/source admission and SDK downloads had
completed; no app build, AVD boot or instrumentation had begun. This is a real
hosted prerequisite failure, not an Android test result. The correction is a new
reviewed source/one-shot authorization, not a rerun of that old source.

The smallest path is attempted first: `-no-window -gpu software -version`, matching
the diagnostic's intended headless/software mode. Official launcher source selects
a headless backend and omits Qt setup in this mode, but this does **not** establish
that the installed headless binary is independent of PulseAudio. A bounded
version-only `-verbose`/process-local `LD_DEBUG=libs` probe identifies the actual
SDK backend and vendor library-search directories. This does not create an AVD.
Unknown backend/loader output, inherited `LD_*` overrides, `GLIBC_TUNABLES` or
system-library preference overrides block rather than guessing a search order.

The controller checks x86_64 ELF identity and complete `ldd` closure for the
launcher, observed backend, and packaged SwiftShader/Vulkan shared libraries.
These checks use the observed SDK search order; conflicting providers, loader
version errors and incomplete output fail closed. If this exact selected closure
already resolves and the version probe succeeded, status is `PREINSTALLED` and
**no APT operation occurs**. This is not proof about every future `dlopen` or the
actual boot's mappings; Android runtime still has to succeed separately.

Only an initial missing set of exactly `libpulse.so.0` permits the optional repair:

- Require the runner's existing Ubuntu archive keyring and APT/dpkg/readelf tools.
  Use official HTTPS Noble, Noble updates and Noble security **main** indexes with
  `Signed-By` bound to a private copy of that existing keyring. No key import, new
  trust, insecure/unauthenticated flag or affirmative prompt is permitted.
- Keep all APT state, indexes, downloads, logs, status snapshot, configuration,
  preferences, authentication-file paths and source/trust snippet directories in
  owned temporary storage. Host APT snippets/hooks are not loaded. The real host
  package database is read and hashed, never installed into or modified.
- Require a warning-free authenticated update, all three expected `InRelease`
  files, and independent `gpgv` signature checks using the existing archive trust.
  APT authenticates index/package hashes through those signed releases. A readonly
  solver simulation permits only new packages in the controller's narrow audio/X11
  allowlist: upgrades, removals and unreviewed dependencies block.
- Download the exact solved versions once, at most 16 packages and 32 MiB of
  `.deb` data. Bind each archive's size and SHA-256 to authenticated package-index
  metadata, then independently compare package/version/architecture control fields.
  Only `dpkg-deb --fsys-tarfile` data is read; no package installation or maintainer
  script runs. Bound total expanded data-tar bytes to 64 MiB and each archive to
  10,000 members. Validate all paths/types/symlinks before manual data extraction;
  reject traversal, absolute/escaping links, hard links, special files and writes
  through symlink ancestors. Archive ownership/mode bits are not imported.
- Verify extracted ELF architecture and canonical SONAMEs. Reject ambiguous
  providers and core/loader/security-critical libraries. Populate a private loader
  directory only with currently unresolved SONAMEs, never replace a provider
  already found in the host or SDK. Resolve the complete selected closure again,
  require each added provider's exact owned path and hash, and verify every prior
  provider remains unchanged. Unknown transitive dependencies block; there is no
  additional download cycle, global library path or fallback install.

The optional overlay is passed only to emulator/version commands. The launcher
prepends its vendor paths; preflight models those observed paths before the private
overlay. ADB, Java, Gradle and other commands do not receive the overlay. Private
APT and extracted data disappear with the controller's temporary directory; no
package/library/key material is uploaded. The receipt records target/tool/keyring/
configuration/source/status/release/package/selected-library hashes, package
versions, signer fingerprints, initial missing SONAMEs and closure-output hashes.

The five-minute phase cap includes probe, metadata, download, extraction and
closure checks, clipped by the overall deadline; individual external commands
receive at most 120 seconds. The 32/64 MiB limits apply to package payloads, **not
APT metadata index bytes**. Metadata acquisition also samples the owned index tree before, during and after
the update: exceeding 256 MiB or 128 entries, or encountering special/symlink
files, fails and cleans up the owned command process. This polling guard is not
a filesystem quota; transient growth between samples can exceed its threshold. Binary data-tar command logs expose only byte
count and SHA-256, never raw archive bytes. No timeout or setup failure proceeds
to app build, ADB startup, boot or tests.

Local validation: 78 host tests pass, including a genuinely compiled shared-library
fixture checked by the local dynamic loader, private APT configuration isolation,
a real child-process metadata-growth cleanup regression, and external-tool-mocked
full setup/receipt/failure orchestration. Those mocks
are explicitly **not** Ubuntu Noble solver/signature/download evidence. This
workspace's Debian host is not the Ubuntu runner; no SDK/emulator or APT download
was run locally. The corrected installed backend and real private-package route
remain **NOT_RUN** until the next reviewed hosted attempt. The stdin regression
retains failing exit semantics while reporting only privacy-safe byte counts and
numeric sanitizer-marker counts; no cause is attributed without hosted evidence.

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
  selects the headless backend for `-no-window`, sets vendor library paths, and
  executes QEMU in place. This supports the scoped preflight and conservative
  same-PID listener check; installed-tool behavior still requires verification.
- [APT configuration order and directories](https://manpages.debian.org/testing/apt/apt.conf.5.en.html),
  [authenticated archives](https://manpages.debian.org/testing/apt/apt-secure.8.en.html)
  and [simulation/download commands](https://manpages.debian.org/testing/apt/apt-get.8.en.html)
  describe the private-config/read-only-solver/authenticated-download primitives.
  [Ubuntu Noble libpulse0](https://packages.ubuntu.com/noble/libpulse0) is the
  narrowly reviewed missing-library package; actual dependencies are solved and
  constrained at runtime, not assumed from a static list.
- [Ubuntu24.04 runner image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)
  lists the preinstalled SDK/JDK. Actual prerequisites are rechecked at runtime.
- [Standard public-runner availability and costs](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
  documents standard public-repository runners as free.
- [GitHub push events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#push)
  document the pushed SHA/ref and omitted per-commit changed-file lists.
  [Workflow path filters](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onpushpull_requestpull_request_targetpathspaths-ignore)
  are combined with branch filters but require only one matching changed path.
- [GitHub run context](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#github-context)
  defines the incrementing run-attempt identity. A rerun is not a fresh automatic
  authorization under this policy.

Before integration, regenerate `SOURCE_MANIFEST.sha256` using the repository's
mechanical manifest protocol. Do not weaken source verification to accommodate
uncommitted or unmanifested diagnostic files.
