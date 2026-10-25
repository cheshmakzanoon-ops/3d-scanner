# Focused software-emulator experiment — not candidate qualification

This **explicitly opted-in, supplementary diagnostic** tests **five NON-UI Android
x86_64 contracts**: four unchanged synthetic JNI/filesystem checks plus one
real-capture-derived numeric PnP regression. It does not reconstruct photographs. CPU software emulation (TCG) is
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
The historical 89d9 invocation failed before any selected test event: that invocation is **FAIL**,
with zero demonstrated test executions/completions. Boot or installation alone is
narrower evidence, not an instrumentation pass.

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

This source-reviewed re-arm requests one current-source **NON-UI**, unsupported
TCG attempt on the corrected pose implementation. The original four methods are
unchanged; a fifth exercises numeric camera-registration JNI with 45 licensed
reference-derived rows. No Activities, rendering, Filament or SAF are exercised.
Earlier four-test passes do not satisfy this five-test scope. The separate
accelerated ATD controller retains exactly its original four methods through a
closed CPU-mode selector mapping; its permissions and launch behavior are unchanged.
KVM access approval remains separate; no accelerated workflow is invoked here.

Automatic execution is limited to the public `cheshmakzanoon-ops/3d-scanner`
repository's `refs/heads/main`, on attempt 1, with all of the following:

- Previous head exactly `f6cdd12b937bc85d3958ca7b6f5dcf8f3035058d`.
- Exact commit message `test: verify corrected pose registration on Android`.
- A normal update, not a created/deleted branch or forced push.
- Event `after`, head-commit ID and `GITHUB_SHA` all identical.
- One non-merge commit whose actual parent is that previous head; the committed
  message must also match the event's explicit opt-in.
- Only modifications to `.github/workflows/software-emulator-diagnostic.yml`,
  `tools/device/software_emulator/diagnostic.py`,
  `tools/device/accelerated_emulator/diagnostic.py`,
  `tests/test_software_emulator_diagnostic.py`, `tests/test_accelerated_atd_diagnostic.py`,
  this document, `README.md`, `docs/MILESTONE_STATUS.md`, `docs/NEXT_MILESTONE.md`
  and `SOURCE_MANIFEST.sha256`; additions only for
  `app/src/androidTest/java/com/realityforge/scanner/CenteredTranslationPnpNativeTest.kt`,
  `app/src/androidTest/assets/pnp/centered-translation-45.json` and
  `app/src/androidTest/assets/pnp/README.md`.
  Both workflow and software controller must change. Wrong statuses, other additions,
  production changes, deletions, renames, unrelated paths and missing history block.

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
| One read-only PackageManager response probe | 60 seconds |
| Each APK install, exactly one attempt (two APKs maximum) | 10 minutes |
| Selected instrumentation invocation | 15 minutes |
| Named read-only factory property/setting snapshot | 60 seconds |
| Read-only device UTC sample immediately before instrumentation | 10 seconds |
| Failure-only logcat and optional app-ANR DropBox history, shared deadline | 45 seconds / 256 KiB each (512 KiB combined) |

Every subprocess deadline is clipped by the remaining controller budget. A later
phase may therefore receive less than its individual maximum. Regular commands
have a 60-second maximum unless explicitly specified. Each APK install has a
600-second cap; there is no retry and its timeout is clipped by the original
overall working deadline. The instrumentation cap remains 15 minutes.
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

The next actual attempt, [run 39935804154, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39935804154),
at source `68e7a843bf44aaa2557cd34281cd5a4a2281d60e`, stopped `BLOCKED` before
build/boot/tests because the checker expected an unobserved quoted `argv[00]`
debug line. The preserved loader tail instead contains paired initialization and
control-transfer records for owned PID 5143's headless backend, and prints emulator
37.2.12.0. Visible RPATH/LD_LIBRARY_PATH searches in that tail belong to crashpad
PID 5147, not the backend. The old controller parsed all 56,618 captured bytes;
only its displayed log was truncated to 16 KiB. That display loss did **not** cause
the parser failure, but prevents a complete replay of its search-path evidence.
The old receipt omitted probe exit/hash/size, so neither an exact probe exit code
nor a completed closure check is claimed retrospectively. No private APT repair,
Android build, boot or instrumentation occurred in that attempt.

The smallest path is attempted first: `-no-window -gpu software -version`, matching
the diagnostic's intended headless/software mode. Official launcher source selects
a headless backend and omits Qt setup in this mode, but this does **not** establish
that the installed headless binary is independent of PulseAudio. A bounded
version-only `-verbose`/process-local `LD_DEBUG=libs` probe identifies the actual
SDK backend and library-search evidence. This does not create an AVD. The probe's
actual `Popen` PID is recorded before reading output. A successful backend must
have exactly one matching initialization/control-transfer pair on that same PID,
in the launcher/backend execution sequence. Other-PID QEMU, duplicate, incomplete,
conflicting or foreign program records block. Crashpad/other child processes never
supply the selected backend or search-path evidence.

Search records are associated with that PID's backend-loading interval, not the
previous launcher exec or a child. Only its observed `LD_LIBRARY_PATH` entries are
reconstructed as that environment variable, preserving their observed order.
`RPATH` and `RUNPATH` remain native attributes of the checked ELF files: their
separate precedence/inheritance rules are handled by the actual loader and are
never flattened into an environment path. SDK paths must remain SDK-contained;
host ELF search tags are not promoted into SDK overrides. If only native SDK ELF
search tags were observed, no LD_LIBRARY_PATH is fabricated. Missing selected
search evidence blocks even when a child supplies plausible-looking SDK paths.

A true initial missing-library failure can occur before backend initialization.
That narrow case requires exit 127, a paired owned launcher startup, no other PID,
and exactly one canonical SDK-backend missing-SONAME loader-error line. Ambiguous
or differently shaped failures block rather than guessing the backend or repairing
libraries. The normal selected-test runtime acceptance gates are unchanged.

Unknown backend/loader output, inherited `LD_*` overrides, `GLIBC_TUNABLES` or
system-library preference overrides block rather than guessing a search order.

The controller checks x86_64 ELF identity and complete `ldd` closure for the
launcher, observed backend, and packaged SwiftShader/Vulkan shared libraries.
These checks use observed SDK LD_LIBRARY_PATH entries plus each ELF's native
RPATH/RUNPATH and system loader behavior; conflicting providers, loader
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
sets its vendor paths; preflight preserves native ELF tags and appends the private
overlay only after observed LD_LIBRARY_PATH directories. ADB, Java, Gradle and other commands do not receive the overlay. Private
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

Host-library/loader coverage includes genuinely compiled shared-library
and RPATH-versus-RUNPATH precedence fixtures checked by the local dynamic loader,
private APT configuration isolation,
a real child-process metadata-growth cleanup regression, and external-tool-mocked
full setup/receipt/failure orchestration. Those mocks
are explicitly **not** Ubuntu Noble solver/signature/download evidence. This
workspace's Debian host is not the Ubuntu runner; no SDK/emulator or APT download
was run locally. The a014 backend/closure checks succeeded without private packages; the actual
private-package route remains **NOT_RUN**. The later 25f attempt verified both installs,
but its instrumentation process crashed before any selected test events.
The preserved old tail proves only the recorded startup pair, not a successful
replay of this new complete selected-process path parser. The stdin regression
retains failing exit semantics while reporting only privacy-safe byte counts and
numeric sanitizer-marker counts; no cause is attributed without hosted evidence.

### Complete bounded probe evidence

The probe has a 256 KiB capture cap. It emits the **entire captured trace**, not
just its tail, as prefixed readable lines and byte-exact base64 chunks under
`RF_LOADER_TRACE_RAW_META` / `RF_LOADER_TRACE_RAW_CHUNK`. Metadata binds byte count,
SHA-256, chunk count and a `complete` flag. Require every numbered chunk exactly
once in order, strict base64 decoding, and matching length/hash before review.
`complete=false` marks partial timeout/overflow capture and cannot establish a
complete probe or a successful setup. This changes neither instrumentation's
separate transcript format nor its strict selected-test proof requirements.

`hostLibraries.launcherProbe` is attached to the receipt before execution; it
records the owned PID, captured byte count/hash, completion and exit when known.
After return, probe exit/size/hash are also recorded before parsing. Thus unknown
backend/path syntax retains diagnostic evidence instead of losing it with parser
failure. Early probe or parser failure still stops before build/boot/tests.
Successful-looking version output alone is never sufficient to pass preflight.

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

CLI syntax was checked against official help/source; the a014 hosted attempt
also exercised these private-server/ownership checks through measured emulator
boot. The 25f attempt additionally installed and hash-verified both APKs. Its
instrumentation invocation failed with no demonstrated selected test executions.
A tool version that differs from the source assumptions can conservatively block
startup; this diagnostic does not fall back to a shared server or relaxed checks.

## Observed install timeout and bounded correction

[Run 39940307810, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39940307810),
at source `a014166f3d6d64762e825d7217d250a92ba02935`, completed the exact-source
APK build and measured API35 / x86_64 / 4096-byte-page emulator boot. Loader
preflight reported `PREINSTALLED` with no private packages downloaded. The emulator
reported boot completion in 827,808 ms. Device identity queries finished at
10:57:53 UTC; the first app-install command emitted zero captured bytes and hit
its fixed 120-second limit at 10:59:54. The terminal receipt is `TIMEOUT`, elapsed
1,404.861 seconds: this was an individual install cap, not exhaustion of the
72-minute controller deadline. Test-APK installation and instrumentation never
started. The built diagnostic app SHA-256 was
`654ee78273337022a649960c16d46ffd24fba8f49d790a3009bc149d24d1b895`;
this is not a verified installed-package hash. No installed APK hashes, test proof
or JUnit success were established.

The same source's [normal verification](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39940307510)
reported 837 passing Android host tests, while each native release configuration
passed 48 CTest and 1,867 pytest tests (170 subtests; 3 skips). These do not imply
a release pass: both aggregate sanitizer Python suites failed 83 tests, the
[separate sanitizer matrix](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39940307541)
hit Python timeouts, and the original device gate remained blocked by its KVM
prerequisite. None of those gates or runtime sanitizer environments is relaxed
by this supplementary diagnostic correction.

Retained evidence hashes (original evidence is not rewritten):

- Full job log: `80e8e8dc2ba8184a9548c0d0e37a3d497d6b75b50dc201e66e472f4441d15ad2`.
- Extracted final receipt: `7c1287f9d9e1ec217a515d1894d9ea191de04ccf9e9af02cd2acec0cb1245934`.

Zero captured output does not locate the stall. ADB's install path can use buffered
stdio and wait for a remote package result; transfer delay, PackageManager/ART
work and post-boot CPU contention remain hypotheses, not diagnosed causes. APK
sizes and the active install's process/timing metadata were not recorded in that
old attempt. Changing transport, retrying a possibly partial install, disabling
verification, or changing emulator/security settings is not justified by this
observation.

The narrow correction keeps the same `adb install -t` operation and one attempt
per APK, with 600 seconds per attempt under the unchanged hard overall deadline.
First, one read-only `cmd package path android` query has at most 60 seconds and
must return exactly the known system framework package path. Its receipt says
`RESPONSIVE`, not idle or guaranteed ready: neither this query nor boot completion
proves that installation will finish. Failure or unexpected output stops setup.

Before each install, the APK is rehashed against the exact-source build receipt.
A per-APK record includes package identity, byte size, SHA-256, start/end time,
elapsed duration, configured/effective timeout and the owned ADB client's PID,
exit/completion/output hash/byte count when known. Partial metadata survives
cancellation and timeout. `COMMAND_SUCCEEDED` means only command completion;
`INSTALLED_HASH_VERIFIED` requires the unchanged post-install installed-base-APK
hash check. First-install failure prevents the second attempt; any setup failure
prevents instrumentation and JUnit. There is no automatic retry or mode fallback.
The subsequent 25f attempt completed both installs within these caps; this does
not guarantee future installation timing. The failed a014 attempt was not retried.

The previous emulator tail also printed its disposable ADB **public** key. This
was not a private signing key, but conflicts with the diagnostic's no-key-output
intent. Known public-key lines are now redacted before readable-log tail clipping;
`displayRedacted=true` marks the display change while its byte count still refers
to the original captured bytes. Original archived logs are untouched. Raw loader
or instrumentation proof containing those markers is rejected before emission,
not silently rewritten or rehashed. Clean raw proof/chunk/hash semantics remain
unchanged. No key persistence, permissions change or credential rotation is added.

Local installation coverage includes single-attempt first/second
install timeout, cancellation, deadline clipping, malformed service responses,
changed APKs, exit-zero/hash mismatch, terminal no-JUnit timeout and real silent
child-process metadata. Host/controller tests themselves do not establish Android
installation or test execution; the distinct 25f hosted observations follow below.

## Observed instrumentation crash and bounded context capture

[Run 39945481835, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39945481835),
at exact source `25f0187c1a593a2db845d132bb1f9d01d050deed`, again built both APKs,
booted API35 / x86_64 / 4096-byte pages, and resolved the headless host-library
closure as `PREINSTALLED` without private APT packages. The read-only PackageManager
probe responded in 3.030 seconds. App and test-APK installs succeeded in 281.937
and 27.995 seconds, respectively. Both actual installed base APKs were subsequently
hash-verified against the exact-source build:

- App, 144,878,589 bytes: `40fb5a3925bb6701eedf5cce8c477c7af1a7e50ca8234df3b6522f5c573adb38`.
- Test, 5,628,801 bytes: `b8269be08b2484e7f0bdba60ed811e629cd0f33d1f08a197dd0621a94621c934`.

Instrumentation was invoked but returned only `shortMsg=Process crashed.` and
`INSTRUMENTATION_CODE: 0`. Its exact 74-byte transcript was reconstructed from one
ordered base64 chunk and verified as SHA-256
`cc376fcd260b55a01f82c04af5e0a9ead88516f600131a38bdea1a3e05b10a8d`.
There were **zero test starts or completions**, no `testProof`, no JUnit success,
and no post-test installed-hash verification. The terminal receipt at
11:50:17 UTC is `FAIL`, elapsed 1,734.137 seconds, with the original error
`Instrumentation crash/failure marker`.

No Android crash/main/system logs were collected before that owned emulator was
stopped. The retained host emulator tail does not identify a Java exception,
native stack, signal, or process-kill reason. TCG warnings are not causal evidence.
Neither an application defect nor a TCG/runtime defect is diagnosed from these
74 bytes. Original evidence remains unchanged:

- Full job log: `fb5b595aa6d0b61800059a82571225b0f806fe55db7d86ec6e262257998949e3`.
- Extracted receipt: `48cda4f0cc09e91081421e64bcb8ce1298b694d54dd35b950deaa296cc9cd335`.

Ordinary CI is separate: the same source's Android host suite passed 837 tests,
and both native release configurations passed 48 CTest and 1,878 pytest tests
(176 subtests; 3 skips). The separate GCC sanitizer Python stage failed 83 tests
(1,795 passed), its Clang counterpart timed out, and the original device gate
remained blocked by KVM. Aggregate verification completed normally by
2026-10-23 12:13:40 UTC: both sanitized CTest stages passed 48 tests, but both
Python stages timed out at 600 seconds without summaries. These observations are **not a general release pass**;
no original gate or sanitizer runtime environment is weakened by this change.

The narrow correction changes diagnostics, not test selection or acceptance.
Before parsing, `instrumentation` retains the command's owned PID, exit when known,
completion, captured-byte count/SHA-256, timing and configured/effective budget.
On an instrumentation command or parser failure, exactly one read-only command
may run while the same owned emulator and private foreground ADB server remain
alive with verified listener ownership:

```text
adb -H 127.0.0.1 -P <owned-port> -s emulator-5580 shell logcat \
  -b crash -b main -b system -d -v threadtime -v year -v UTC -t 1000
```

It requests at most the last 1,000 interleaved records from those three buffers,
with a 256 KiB captured-byte cap and 45-second timeout clipped to the unchanged
72-minute controller deadline. There is no buffer clear, property change, root,
protected tombstone read, retry, second instrumentation attempt, or mode switch.
Cancellation, exhausted **global** deadline, lost process/listener ownership, or
missing exact source/device/installed-APK binding skips capture. An original
**local** instrumentation timeout can still collect this bounded context if global
time remains; the original terminal result stays `TIMEOUT`. Capture timeout,
nonzero exit, child signal, overflow, or renderer failure cannot mask the original
instrumentation exception or produce JUnit. This does not override external job
cancellation/termination: a cancelled job remains non-passing even if a failure
receipt was already emitted, and forced termination can prevent the final receipt.
A successful instrumentation proof does
not collect this extra context. Normal owned-process cleanup still runs.

`RF_CRASH_CONTEXT_META` and the final receipt's `crashContext` bind diagnostics to
the source, installed APK hashes, device serial and original instrumentation
process metadata. `capturedBytes` / `capturedSha256` describe **only the original
bounded captured bytes**; `captureComplete=false` marks a partial capture. No raw
crash-log base64 is published. These raw captured bytes cannot be reconstructed
from a redacted display, and their hash is not an independently retrievable raw
proof. Exit zero means only that logcat completed, not that any test passed or
that every relevant crash record was retained in the circular buffers.

The separate `displayBytes` / `displaySha256` bind a canonical diagnostic display:
UTF-8 replacement decoding, known disposable-public-key redaction, normalized
line separators, stripped terminal controls, and removal of terminal newline
characters. Reconstruct it by joining the payloads after
`RF_DIAGNOSTIC_LOG device-crash-context-display | ` with `\n`, **without adding a
terminal newline**, then UTF-8 encode and check its byte count/hash. Every output
line is prefixed against workflow-command injection. Recognizable unexpected
credential/private-key markers withhold the entire display; an expanded display
over 256 KiB is also withheld. These checks are not a general secret detector:
collection is restricted to the owned synthetic emulator without user accounts,
and no arbitrary user-device logs, credentials or private files are requested.
Display normalization/redaction never changes instrumentation's exact raw proof.

The logcat path ran on 9628 as described below. The diagnostic host tests cover parser failure,
nonzero/timeout/signal paths,
partial bounded output, original-exception preservation, cancellation/global
clipping, missing ownership/binding, display secrecy and actual prefixed-display
hash reconstruction. Real host child regressions verify timeout/overflow/signal
metadata and owned cleanup; they are not Android crash-cause evidence.

## Historical startup ANR and ordinary AOSP image experiment

[Run 39951908860, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39951908860)
at source `9628a5b28501183b0d2fd78daf2cb77ba53da02f` built, booted and hash-verified
both installed APKs. Headless libraries were `PREINSTALLED`; no private APT
packages were needed. App/test installs took 179.122 / 20.661 seconds. The app
APK hash was `05631abe977ac20d1863edd6ecff8fb30404230f6679f54d581708bb18184048`;
test APK hash was `4f5ecad79b41e7f8d69b2f581c2b05b513162e4124d9e4805968bf0324cbaf47`.
Instrumentation ran for 48.901 seconds and again returned the exact 74-byte
process-crashed transcript with SHA-256
`cc376fcd260b55a01f82c04af5e0a9ead88516f600131a38bdea1a3e05b10a8d`.
The terminal result at 2026-10-23 12:32:45 UTC was **FAIL**, with zero selected
test starts/completions and no JUnit proof.

This time Android context established the immediate failure mechanism. At guest
12:32:33.442, ActivityManager reported startup ANR for scanner PID 3273, followed
by its `bg anr` kill at 12:32:33.946, Zygote's signal 9 exit and the instrumentation
crash report. Earlier SIGQUIT 3/tombstoned messages describe ANR stack collection,
not evidence of a fatal native crash. The captured log does **not** contain the
scanner main-thread stack. Native test/JNI execution is not demonstrated.

The same log reports 100% guest CPU utilization and CPU-pressure `some avg10=85.52`,
with system_server, scanner, launcher and other system/Google processes active.
Scanner class-loader setup/context-mismatch warnings precede the ANR. These
observations justify investigating startup contention; they do not establish
whether TCG, ART/classloading, app initialization, or their combination is the
underlying cause. The tiny lazy application composition root is not changed.
The mechanism of the earlier 25f crash remains unproven; no retroactive diagnosis
is made from this later run.

The complete captured context was 196,675 bytes, SHA-256
`e9812100dcce6b28bd115311908e917848e2e90d12dbdedaa6abad70c13247b9`.
Its independently reconstructed canonical **diagnostic-only display** was
196,674 bytes, SHA-256
`a81fa7856b29833d977738034472ba77f3c7732d235c5876cdd5ea80ca0dc835`.
Do not append a newline when reconstructing that display; raw and displayed
hash semantics remain separate even when one added newline happens to reproduce
the recorded captured hash. Original failure evidence is preserved.

### Image choice and factory behavior

That experimental runtime change selected ordinary
`system-images;android-35;default;x86_64`, **revision 2**, instead of Google APIs
revision 9. API/ABI/tag/revision must match both installed SDK metadata sources;
a newly published different revision blocks rather than silently changing the
experiment. All existing API35 / x86_64 / 4096-page, source, APK, four-event-pair,
license and system-image hash gates remain. This is a **different diagnostic
image**, not equivalent Google-services runtime, physical-device validation,
ARM64/performance evidence, or original-gate qualification. No application code,
ART compilation, startup warmup, PM queue wait, service disabling, watchdog
extension, permission setting or security change is combined with it.

The official repository XML metadata inspected on 2026-10-23 lists:

| Image | Stable revision | Archive bytes | Official metadata SHA-256 |
| --- | --- | --- | --- |
| [Ordinary AOSP default API35 x86_64](https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml) | 2 | 782,404,023 | `7b119fdc282a65432aab52e8bbc64546f7fb6ec74e79e235633c60d06564b111` |
| [AOSP ATD API35 x86_64](https://dl.google.com/android/repository/sys-img/aosp_atd/sys-img2-3.xml) | 1 | 690,340,274 | `2c5ee64ad6720bfd09745c5974d03759fd84a5e72de6fea4231b6d00eab619c8` |
| [Prior Google APIs API35 x86_64](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml) | 9 | 1,738,815,903 | `858c1f39632fa98235f13ebfcf2f362d5a19eeaed6c5f9c991425f257809e518` |

The selected official archive is `x86_64-35_r02.zip` under the default metadata's
base URL; its repository-declared SHA-1 is
`2d857d170c0d1b827149565da34b3383e5306f7f`. No image archive was downloaded or
installed during this host investigation. Runtime SDK metadata and extracted
system/kernel/ramdisk/vendor SHA-256 values remain required in the receipt.
All three selected package entries reference `android-sdk-license`; their license
text is byte-identical, SHA-256
`1f8729233617b193fd619213792ae16a41b95d2bbbf525dfe66998252ba68b16`.
This is **not proof of the runner's acceptance**. Only its preexisting unchanged
license records and closed-stdin official SDK download admission are allowed;
new terms or a missing acceptance still block, with no affirmative response.

ATD was not selected for that stock-image attempt. Official [ATD documentation](https://developer.android.com/studio/test/managed-devices)
describes deliberate app/service/rendering reductions. Android15's
[slim product](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/android15-release/product/slim_handheld.mk)
replaces major UI components with FakeSystemApp (source SHA-256
`72e2b94be014d53e305981019a6ba9d4d5e8567a3f1ae9147ae31384a728b623`), and
[slim vendor configuration](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/android15-release/slim/vendor.mk)
disables HWUI drawing (SHA-256
`ce120d024731944361e1618ef50664ce15f88011d8e34c1e21158ea496e41c51`).
The inspected product/overlay subset did not establish an ATD ANR-timeout change,
but also cannot prove the complete shipped factory property set. Neither ATD nor
ordinary AOSP watchdog equivalence to the prior image is asserted.

The next diagnostic records named factory properties and read-only global
settings, including `ro.hw_timeout_multiplier`, `ro.debuggable`, build type/tags,
`debug.hwui.drawing_enabled`, `debug_app`, `wait_for_debugger` and
`watchdog_timeout_millis`. Empty values remain empty, not invented measurements.
[AOSP Build](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/os/Build.java)
(source SHA-256 `ce3c4c46051d0f845491ab39b39d3291f4e3994cea1d2271aa2a91bf0713e8d9`)
uses a default multiplier 1 when the property is absent;
[ActivityManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/am/ActivityManagerService.java)
(SHA-256 `4eaf4c3b930071827705079c69ea15c4dddce375a6a52ef7b5cd75803ff10db5`)
applies it to the 15-second bind-application timeout with one bounded CPU-delay
extension. The prior receipt did not observe this property, so comparison is
explicitly `UNVERIFIED`. No property is written or overridden.

A selected scanner `debug_app` blocks: AMS marks that process debugging, and
[ProcessErrorStateRecord](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/am/ProcessErrorStateRecord.java)
(SHA-256 `adfa40890deedf51f7df6ffd8e3179c51db15751f22da9265857edb935592c6d`)
skips its ANR handling. A factory userdebug build alone is not that mode. The
[system_server Watchdog](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/Watchdog.java)
(SHA-256 `2bf6c417c43e8f408985c1fb4db802551b9ffc47c04f923ad8ae6345d167177d`)
is distinct from app startup ANR handling; its setting is recorded, not mislabeled
as an app-ANR-disable switch. These targeted observations are not a proof that
all possible platform watchdog/security behavior is equivalent.

### Additional read-only ANR stack context

After an instrumentation failure, logcat now requests `-v year -v UTC` alongside
threadtime, using [documented modifiers](https://developer.android.com/tools/logcat).
If a complete bounded log contains a scanner ANR, at most one additional command
may read the same owned device while it is alive:

```text
adb -H 127.0.0.1 -P <owned-port> -s emulator-5580 shell dumpsys activity lastanr-traces
```

It **shares the original 45-second context deadline** with logcat and the failure
UTC sample, clipped by the 72-minute global deadline; it does not receive another
45 seconds. Each capture is capped at 256 KiB (at most 512 KiB combined). A read-only
10-second-capped device `date -u` sample immediately before instrumentation and
another within the failure budget provide a common device-time basis. Missing
samples, clock jumps, cancellation, expired time or lost ownership cannot create
an attributed stack. Instrumentation success performs no failure captures.

[ActivityTaskManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)
(SHA-256 `37ab36dc1c57988da482a1803397d07af254cbc4ee26c6ec6d8d5eb633722199`)
implements this service read using its existing permissions and chooses the most
recent ANR file, which may belong to another process. No direct protected-path
read, root/run-as fallback, grant, buffer clear, retry or private user device is
used. Permission denial remains unavailable/unassociated diagnostic evidence.
A DropBox read was considered but is not combined here; its tag filter alone
would not bind a scanner process.

`lastAnrTraces` / `RF_LAST_ANR_TRACES_META` retain exact source, installed APKs,
serial, instrumentation process, bounded command metadata and separate captured
versus display hashes under the existing redaction/secret-withholding policy.
The display prefix is `RF_DIAGNOSTIC_LOG device-last-anr-traces-display | `;
reconstruct it using the same no-terminal-newline rule. The original test failure
and all strict test-proof gates remain authoritative, regardless of capture result.

Association requires one scanner startup ANR event with matching PID/reason and
same ActivityManager timestamp/emitter, inside the measured device UTC interval;
one complete ART trace block with that exact process/PID, explicit timezone,
nearby timestamp and main-thread section; and no duplicate/conflicting matching
blocks. [ART SignalCatcher](https://android.googlesource.com/platform/art/+/refs/heads/android15-release/runtime/signal_catcher.cc)
and [GetIsoDate](https://android.googlesource.com/platform/art/+/refs/heads/android15-release/libartbase/base/time_utils.cc)
define the process/header/nanosecond timestamp format. Foreign, stale, partial,
ambiguous, clock-discontinuous or unfamiliar formats remain `UNASSOCIATED` and
must not be presented as the scanner's causal stack. Even associated output is
`ASSOCIATED_DIAGNOSTIC_ONLY`, never test or qualification proof.

As of 2026-10-23 12:58:18 UTC, source 9628's ordinary Android job passed 837
host tests; both release configurations passed 48 CTest and 1,891 pytest tests
(207 subtests, 3 skips). Both matrix sanitizer CTest stages passed 48; their Python
stages timed out at 600.116 / 600.151 seconds without final summaries. The original
KVM device gate remained blocked, and aggregate native execution was still
running. No terminal aggregate result or general release pass is asserted.

Final update at 2026-10-23 13:03:44 UTC: all workflows were terminal, not
cancelled. The aggregate native job failed normally: all four configurations
passed 48 CTest tests; both release Python stages passed 1,891 tests; both
aggregate sanitizer Python stages **completed** with 83 failures / 1,808 passes /
3 skips / 207 subtests each. The 83 failure identities matched a014 exactly.
Those completed aggregate results are distinct from the separate matrix's two
600-second Python timeouts. Source-bound aggregate artifact `12502843079` had
SHA-256 `3d30b5d5d28b802fa9288e6af770587e640befa30ab86d5d9d4316f25ad82a8d`.
These failures and the blocked original KVM gate remain failures, not waivers.

Ordinary AOSP installation/boot/runtime and the new lastanr-traces command are
**NOT_RUN locally**. Host regressions exercise policy/parser/budget/privacy logic,
not Android behavior. The previous 9628 logcat capture is actual hosted evidence;
its success does not establish this new service command's runtime availability.

## Stock-AOSP failure and ATD-only non-UI diagnostic

[Run 39958997198, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39958997198)
at exact source `89d9e3387bb19e5243017f28461040372615736b` booted ordinary AOSP
API35 / x86_64 / 4096-byte pages and hash-verified both installed candidates.
App/test installs succeeded in 215.377 / 20.393 seconds; their SHA-256 values were
`324c01b3cf5ea43294e760c170338d399b93e20aa44b877b16e910db6660db4f` and
`7237f71304713169017779bdd8969642c8146744ddfc9dfa5ae2c42e382cf849`.
The 50.433-second instrumentation invocation again produced only the same 74-byte
crash transcript, SHA-256
`cc376fcd260b55a01f82c04af5e0a9ead88516f600131a38bdea1a3e05b10a8d`.
The terminal result at 2026-10-23 13:31:27 UTC was **FAIL**, with zero selected
test events/completions and no JUnit proof.

The new Android log identifies scanner PID 2743 startup ANR at 13:31:10.142,
followed by the background-ANR kill at 13:31:10.430 and signal 9 exit. The latest-ANR
service returned a subsequent `com.android.phone` report, not the scanner's
stack; the receipt correctly remained `UNASSOCIATED`. Historical scanner mentions
inside that report are not its main-thread stack. The underlying cause remains
unresolved; neither a native-test failure nor a TCG-only cause is established.
Actual factory `ro.hw_timeout_multiplier` was empty; `debug_app`,
`wait_for_debugger` and `watchdog_timeout_millis` were `null`. The observed ordinary
image did not expose an explicit multiplier or debugger setting to blame.

Preserved evidence SHA-256 values:

- Complete job log: `eec732935f799d18680f4a4c28fd282cf9fee56d59101c5a0c40b67587994506`.
- Extracted receipt: `6d1b0ba9324438741e29424be4c016df125dc12e421d10d384b372af41709bcd`.
- Canonical logcat display, 158,252 bytes: `66f6cd07c88981d5927a503adc3e819199ff0e714466cb65f6e00d79cceb7a40`.
- Unassociated latest-ANR display, 3,249 bytes: `bf2b495b12dce3a254f83afd3c16eba5a4384a399502db833cf5e3054a1de6b6`.

As of 2026-10-23 13:59:18 UTC, the 89d9 aggregate native workflow remained
in progress; no final aggregate result or release pass is inferred. Publication
of another controlled attempt waits for its actual terminal state.

### One reduced-background image, unchanged assertions

The next supplementary experiment selects official
`system-images;android-35;aosp_atd;x86_64`, **revision 1**, rather than repeating
stock AOSP. ATD deliberately stubs/removes UI applications and reduces background
work; this is a materially different runtime profile, not equivalent full-phone
behavior. All four unchanged methods use synthetic JNI arrays or the app's private
cache filesystem. Their unconditional JUnit assertions exercise model/support,
input validation, scoped cancellation, cache publication/hit/corruption/eviction
and an original-file canary. They do not launch Activities, draw, use Google
services or call removed UI apps. ATD therefore does not inherently replace or
disable these explicit checks. It cannot establish UI/platform-service behavior,
stock-image equivalence, physical capture, ARM64, graphics, performance, 16 KiB or
original-device-gate qualification. All existing qualification fields remain
`NOT_RUN`; original blocked/failing gates are not substituted.

No app/test implementation, ART compilation, warmup, PM queue wait, manual service
change, property write, debugger, permission/security setting or new SDK-license
acceptance is combined with this image change. Four exact method identities,
starts/completions, zero skips/failures, exact installed APK hashes, source and
SDK/system-image hashes remain mandatory. The original 72-minute controller /
75-minute job and all phase/cleanup bounds remain. This is one new source-reviewed
attempt, not a rerun of an old failed invocation.

The official image/license metadata table above still applies. Additionally,
bounded HTTP 206 Range reads of the
[official ATD archive](https://dl.google.com/android/repository/sys-img/aosp_atd/x86_64-35_r01.zip)
retrieved only its ZIP directory and three small metadata entries, not system or
vendor images. Range offsets/lengths, declared ZIP CRCs and decompressed lengths
were checked. This does **not** verify the whole 690,340,274-byte archive digest,
and no emulator/image installation occurred during research. Selected files:

| Shipped metadata | Bytes | SHA-256 |
| --- | --- | --- |
| `build.prop` | 4,774 | `a03475e2367f60d68cbf2385314be5d6f88e78aefa3a9c94d7bcc55fa38e0470` |
| `source.properties` | 296 | `7c2bd52d9282f53fc24a57c9a1738f17ff2c51d436c0e8f9272ed0208c06abc5` |
| `advancedFeatures.ini` | 758 | `b293a499caf9a3a2b0ba8e95fbc3d9113c8d3c33da27af59c6284e5f81119e5e` |

The shipped system build properties identify `sdk_slim_x86_64` /
`AE3A.240806.019` / build 12368160, enable ordinary JIT, set `ro.secure=1`,
`ro.debuggable=1`, `ro.force.debuggable=0`, and disable HWUI drawing. They contain
no timeout multiplier or ANR-disable setting. Inspection of the Android15
[slim product](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/android15-release/product/slim_handheld.mk),
[vendor configuration](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/android15-release/slim/vendor.mk)
and [FakeSystemApp sources](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/android15-release/slim/FakeSystemApp/src/)
found UI stubs, not an ANR suppression change. These selected files do not prove
every shipped vendor or runtime setting; full watchdog equivalence remains
`UNVERIFIED`, not inferred from missing properties.

Before instrumentation, actual read-only factory admission now requires:

- `ro.hw_timeout_multiplier` empty or exactly `1`;
- `debug_app` empty/`null`; `wait_for_debugger` empty/`null`/`0`;
- `watchdog_timeout_millis` empty/`null`, matching the observed absence of an
  explicit stock-image override (this is a system_server setting, not an app-ANR switch);
- `ro.secure=1`, `ro.force.debuggable=0`, and reviewed ATD `debug.hwui.drawing_enabled=0`.

Unknown or relaxed values block; the controller does not repair them. The factory
userdebug flag alone is not an attached debugger. This narrowly verifies known
controls, not every possible Android watchdog behavior. Existing licenses must
still already be accepted and unchanged; official SDK downloads have closed
stdin and stop on new terms. Actual ATD factory admission/runtime is **NOT_RUN**
until a hosted attempt verifies it.

### Replace latest-file lookup with app-tagged DropBox history

The 89d9 log actually reports `data_app_anr` being added at 13:31:10.451. The next
failure capture **replaces** the latest-file service call with one read-only:

```text
adb -H 127.0.0.1 -P <owned-port> -s emulator-5580 shell dumpsys dropbox --print data_app_anr
```

The [Android15 DropBox service](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/DropBoxManagerService.java)
(source SHA-256 `c70e14661a3a74afac26ce689802d02296d6ab2860edf35bcf61c492bd45e8a0`)
implements this tag filter and full-text read through existing shell DUMP/usage
access. No app helper, root, run-as, protected-path open, permission grant or
service configuration is used. Tag selection excludes `system_app_anr` such as
phone's report, but does not itself identify a particular third-party app or PID.
Records can be absent, asynchronously pending, overwritten, truncated or denied;
none is retried or treated as test success.

Logcat, the failure UTC sample and this one DropBox read share the unchanged
45-second context deadline. Logcat and DropBox each have a 256 KiB cap, at most
512KiB combined, clipped by the global deadline. Success invokes no failure
capture. Cancellation, lost ownership or expired time skips further collection;
partial/nonzero/timeout/renderer failure never masks the original test failure.

The new `appAnrDropBox` / `RF_APP_ANR_DROPBOX_META` receipt binds source, installed
APKs, device and invocation metadata. Display uses
`RF_DIAGNOSTIC_LOG device-app-anr-dropbox-display | ` and the same canonical
no-terminal-newline reconstruction, secret withholding and separate raw-captured
versus display hashes. No raw crash/DropBox base64 or user-device data is published.

Association requires a complete `data_app_anr` text entry with unique exact
`Process`/`PID`/`Subject`/build headers, the **identical** startup Reason from the
selected logcat event (including ProcessRecord identity/user suffix), a complete
`Data File`/`End Data File` section, and one matching complete ART main-thread
block inside the measured device UTC interval. An optional explicit-zone header
Timestamp must also agree. Lost contents, `[[TRUNCATED]]`, I/O-error markers,
missing footers, foreign builds/processes/PIDs, duplicate entries or missing
clock/stack evidence remain `UNASSOCIATED`. The entry's timezone-less display
heading is not substituted for the explicit-zone ART timestamp.

Actual 89d9 year/UTC logcat lines include a `+0000` column. The association parser
now requires that observed grammar; the old fixture omitted the column and would
have conservatively missed even a matching trace. The recorded-line regression
uses an explicitly synthetic matching ART body, not a recovered scanner stack.
Phone's actual report remains unassociated regardless of this parser correction.

[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo#getTraceInputStream())
and [own-UID exit history](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int))
could expose an app's retained ANR trace without new permissions, but require
running app-owned code and may return null after circular-buffer eviction. No
new helper APK, app modification or second instrumentation invocation is added
for that alternative. The narrower DropBox command is **runtime-unverified**;
even a successfully associated record is diagnostic context, never four-test proof.

The final focused host suite passed 141 tests and 290 subtests (130 diagnostic
tests plus 11 native-process environment-boundary checks). Independent review
passed all 130 diagnostic tests and 290 subtests. These are host checks, not ATD
execution evidence.

## Exactly five software-mode tests (four original methods unchanged)

All methods in `agents.a037.CalibratedInitializerNativeTest`:

- `calibratedPlaneReportsModelAndSupportWithoutChangingLegacyContract`
- `calibratedJniRejectsMalformedDomainShapeAndMisalignedPixels`
- `cancelledCalibratedCallHonorsScopedOperationLease`

And `agents.a033.FeatureCacheDeviceTest`:

- `privateAndroidCacheHitsCorruptionRecomputesAndEvictionPreservesUnownedFiles`

And `CenteredTranslationPnpNativeTest`:

- `realNumericRowsKeepStrongSupportAcrossBoundedOriginShift`

The fifth test loads a hash-pinned, numeric-only 45-row fixture derived from
validated replay `pnp-replay-4b239.json` (SHA-256
`d7dcb3daa08de6be743da959d05e19599ce7aa2ae01cc3b20c8f5f9a89808361`).
At the original threshold `5.113144661933157E-4`, actual cancellable Android JNI
must return >=40/45 support, with proper rotation, positive depth and strict
reprojection for every reported row. A bounded origin shift checks
`t_shift = t - R*d`; native tokens are owned and finished in `finally`.
No exact hypothesis-count expectation is imposed across ABIs. Asset attribution,
numeric derivation and license are retained alongside it; no JPEG, database or
descriptor data is packaged. Shape/finite/bounds/hash checks fail closed.

Each selector is fully qualified under `com.realityforge.scanner`. No camera,
Compose, viewer or SAF activity is launched. Four synthetic checks plus this
reference-derived numeric regression do not establish real-photo reconstruction,
quality, complete objects, UI behavior or ARM64 portability.

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

- Each of the five exact software-mode method identities, once, with one matching start and
  successful terminal event, consistent totals and sequential indices.
- No failure, ignored test, assumption failure, duplicate, missing event, crash
  marker, incomplete output, unexpected selector or extra test.
- Exactly one `OK (5 tests)` summary and one final instrumentation code `-1`.

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
do not establish the five-test software result. Host unit tests exercise parser/policy and
process control only; they are not Android execution evidence.

## Reviewed official references

- [Android Emulator command-line options](https://developer.android.com/studio/run/emulator-commandline)
  document unsupported TCG and its explicit opt-in, headless mode and RAM settings.
- [Graphics versus VM acceleration](https://developer.android.com/studio/run/emulator-acceleration)
  documents current software graphics modes; these do not replace CPU acceleration.
- [Android command-line tests](https://developer.android.com/studio/test/command-line)
  documents class/method selection with `am instrument -e class`.
- [Logcat documentation](https://developer.android.com/tools/logcat) identifies the
  main/system/crash buffers and threadtime format. [AOSP logcat source](https://android.googlesource.com/platform/system/logging/+/refs/heads/main/logcat/logcat.cpp)
  documents read-only `-d`, `-t N` and repeated buffer selection; actual installed
  Android logcat and latest-ANR reads executed on 89d9 (the latter was a foreign
  report). The replacement app-tagged DropBox read remains runtime-unverified.
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
- [Linux dynamic-loader semantics](https://man7.org/linux/man-pages/man8/ld.so.8.html)
  distinguish RPATH, LD_LIBRARY_PATH and RUNPATH precedence and inheritance.
- [AOSP ADB install implementation](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/adb_install.cpp)
  documents transport selection, buffered progress printing and remote status waits.
  [Android15 PackageManager shell source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)
  implements the read-only package-path query; its response is not an idle-state guarantee.
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
