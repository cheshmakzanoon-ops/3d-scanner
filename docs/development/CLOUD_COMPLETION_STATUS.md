# Cloud completion campaign — 21 October 2026

## Goal and truth boundary

Deliver a usable offline Android photo-to-3D scanner APK with the approved complete
capture/import, reconstruction, studio and export workflows. Work is performed in
the assistant's own cloud workspace. The user requests continued development and
five-minute progress messages while execution is active. This is not a claim of
uninterrupted infrastructure availability or a finished product.

## Recovered work

The starting `main` was `ad302919`; substantial subsequent work existed only on
agent branches. This campaign preserves and integrates those implementations rather
than reimplementing them or treating branch receipts as current verification:

- Production integration foundation `561f5e77`.
- Audited Android agents 001–030 aggregate `b684f92b`.
- Native agents 031–060 aggregate `2f18a4f5`, under integration.
- Export/studio/UI agents 061–080 remain to integrate and qualify.

Local integration commits and regression repairs do not certify Android runtime
behavior. Historical receipts under `docs/agents` describe their original source,
not this combined candidate. Source manifests are regenerated after changes.

## Newly identified integration repairs

- Project lease ownership across cancellable dispatcher return.
- Failed reader-metadata admission rollback.
- Production wiring of bounded, identity-bound, checksummed feature caches.
- Invalid surface JSON/export rejection before overwriting output files.
- Preserve expanded native surface evidence across a versioned checkpoint schema.
- Preserve bounded image decoding and distinguish absent from malformed EXIF.

## Validation ledger

- First combined foundation: 13 host CTest targets passed locally.
- Focused surface validation: 8 tests passed with GCC and ASan/UBSan/
  float-cast-overflow. LeakSanitizer unsupported by current sandbox tracing.
- Expanded native aggregate: rebuild in progress; checkpoint-layout incompatibility
  discovered, not hidden by relaxing acceptance.
- Android SDK/NDK and verified Gradle/JDK are being prepared in the cloud.
- Combined Kotlin/JVM, lint, APK build, emulator and physical ARM64 checks pending.
- No physical-object accuracy, complete-object coverage, thermal/battery or store
  readiness claim. Physical capture/reference evidence and release signing remain
  distinct from a locally buildable test APK.

## Validated integration checkpoint (02:31 UTC)

All existing 061–080 runtime branches are now integrated. The application has real
library/curation/recovery routes and publication-aware observed OBJ ZIP/STL
exports; these are not placeholder success buttons. Originals and source results
are not rewritten by export. Arbitrary STL scale requires explicit consent.

Evidence from this cloud execution:

- 45/45 native CTest targets passed after integration and export-header correction.
- Broad Python run: 977 passed, 3 skipped; source-manifest check was excluded during
  edits, then separately passed with the full tracked source set.
- 622/622 actual Gradle JVM tests passed, with no skipped JVM cases.
- Android lint passed after replacing ineffective Kotlin opt-ins with AndroidX
  opt-ins; lint was not disabled or baselined away.
- Debug application APK and instrumented-test APK compiled and packaged for the
  configured ARM64 and x86_64 architectures.
- Actual APK signature, network-permission exclusion, backup exclusion, ZIP/ELF
  16 KB alignment, and native inventory checks passed.
- The refreshed debug dependency audit validated 131 resolved runtime modules,
  114 packaged notice files and both ABI sets; full Filament attribution included.

A preview was delivered as split ZIP parts because the messaging attachment size
limit rejected both the raw APK and the complete ZIP. This is a test-signed
engineering preview, not a production release. Rebuilt package evidence is retained
under `tools/dependencies/evidence/debug/filament-candidate-package-audit.json`.

### Still open (not waived)

Actual emulator/app/JNI/complete-session execution is underway. This cloud machine
has no exposed KVM accelerator; simultaneous emulator/build activity exhausted
resources, so runtime checks and Gradle builds are now sequential. Physical ARM64
capture quality, thermals, memory and real-object/reference comparison remain
unverified. A build is not proof of scan quality.

The supported photo count remains 2–12. Larger profiles need bounded pair matching,
frontier/checkpoint integration, supported multiview windows and measured resource
admission; they are not enabled by merely increasing a constant. Advanced frontier,
refined-depth and derived edit/LOD workflows still have runtime integration gates.
The complete product scope in PROJECT_PLAN.md remains the target.

## First API 35 ATD runtime results and repairs (05:43 UTC)

The app reached onboarding in the official API 35 AOSP ATD emulator. Four actual
instrumented checks passed: grayscale JNI, translated-camera recovery, third-pose
registration, and stale-worker command fencing. This is emulated x86_64 evidence,
not physical ARM64 capture qualification. ATD disables ordinary screen rendering;
a black screenshot alone is not proof of an application rendering failure.

Observed export failed before JNI with `UNSAFE_MESH_SOURCE`: the test allocated
its fixture beneath an Android cache alias. Trusted staging roots and production
cache target allocation are now canonicalized; untrusted source/target alias and
symlink checks remain strict. Real repository/publication source paths already
originate at a canonical ProjectPathPolicy root. Four focused JVM exporter tests
pass, including cache-root alias acceptance and source/target symlink rejection.

The synthetic complete-session test did not complete within its existing bound.
Only two of three feature caches existed; project deletion then masked the primary
failure because service-owned reconstruction still held its lease. The fixture now
cancels its own active job, waits boundedly for shutdown, and retains the original
failure with cleanup failures suppressed. No lease protection or timeout acceptance
was weakened. An optimized test-signed release app/test build is underway; the
complete scan and export device reruns remain pending, not passed.

## Optimized test-signed packaging checkpoint (05:58 UTC)

Both the release application and release instrumented-test APK now build together
with R8 and strict dependency verification enabled. Release-test dependency locks
were missing configuration memberships: regeneration adds only those memberships,
with no dependency coordinate/version additions, removals or changes. Three
resolver-candidate `.module` metadata checksums were verified against official
repository bytes/published checksums; this does not approve dependency downgrades.
The final strict assembleRelease + assembleReleaseAndroidTest rerun succeeded.

Actual shrunk DEX inspection retains NativeAssetExports.writeObserved and the
typed NativeCore bundle-adjustment native signatures. These are packaging checks,
not proof of a completed scan; optimized emulator reruns remain pending. Test
signing is not production-signing qualification.

GitHub main publication was verified by fetching commit `6e4c2e88` and comparing
its complete tree to the local runtime-fix checkpoint. Redundant delivered local
APK/ZIP/source archives were removed only after verification; active source,
build tools, unintegrated work and runtime evidence were retained.

## Integrated sparse finalization and observability increment (06:11 UTC)

The next candidate considers every admitted photo pair (maximum66), persists and
reuses the complete considered pair schedule, and distinguishes summary similarity
from native geometric connectivity. Matching-cache format and geometry policy
identities changed, so incompatible results cannot be reused silently.

Final sparse processing now consumes the complete accepted stable-track snapshot.
The production2–12-photo admission remains unchanged; final global BA requires at
least3 registered cameras. A shared fixed-pose triangulation window supplies seeds
validated against every original source observation, followed by fixed-intrinsic
typed BA with a pose-and-baseline gauge. Camera IDs, original distorted pixels,
source-track IDs, rejection accounting and output/checkpoint camera ordering are
validated. Invalid/disconnected geometry cannot masquerade as successful global BA.

Publication is prepared on deep-cloned JSON/cameras and source observations.
Fallible progress/validation completes before switching to the new geometry;
late failure leaves the verified anchor fallback unchanged and honestly labeled.
Primitive row/seed buffers avoid large boxed packing intermediates.

Ordinal-only feature diagnostics expose cache/native-call phases and monotonic
elapsed/cancellation-cleanup timings without filenames or content identifiers.
OpenCV SIFT remains explicitly noninterruptible during its synchronous call;
existing cancellation checks clean up afterward. No timeouts or quality limits
were relaxed. Both resource tiers already request one OpenCV thread.

Integrated source matches tested draft bytes. Four focused finalization JVM tests,
five actual production host-JNI tests, and the18 existing coordinator regressions
passed independently. The12-camera synthetic fixture preserves576 observations;
late-publication fault injection preserves original poses/point JSON/observations.
Full SparsePipeline direct Kotlin compilation and40 active source contracts passed.
These do not replace the pending actual Gradle/lint/Android instrumentation gates.
See reports/cloud-sparse-finalization.json. Synthetic30-camera coordinator tests
are experimental finalization evidence only, not production photo admission or
physical-object quality evidence.

The optimized baseline installed after recovery from emulator system-service ANRs,
but both targeted instrumentation launches failed before executing tests:
AndroidJUnitRunner referenced androidx.tracing.Trace, which target-app R8 removed
while the separate test APK still called it. This is a real release-test packaging
failure, not attributed to emulator slowness. A narrow keep rule preserves only
that class and its three observed runner entry points, with optimization retained.
A focused configuration regression was added; runtime verification remains pending.
The45-target native CTest suite passes again after the Kotlin integration.

### Actual integrated compilation gates (06:19 UTC)

The complete next-candidate Gradle run passed:662/662 JVM tests with no failures or
skips, Android lint with no Error/Fatal issues, and instrumented-test Kotlin
compilation. The broad Python suite passes988 tests, with3 skips and the manifest
case excluded during edits; manifest verification and the full42 focused
source/configuration contracts pass separately. An initial Python invocation used
the compiler environment rather than the pinned QA environment, so missing Trimesh
was corrected by using the installed QA interpreter, not by skipping export tests.
The README's explicit two-to-twelve disclosure now retains the runbook invariant.
Release packaging and actual Android instrumentation of this increment remain
pending. No physical capture, whole-object quality or larger-input claim is made.

### Previously skipped host gates executed (06:24 UTC)

All three prior optional host skips now pass in one sequential invocation (3 passed,
0 skipped): actual SDK software-Vulkan dispatch/CPU fallback, real Kotlin parser
with pinned Android JSON, and the real JVM/JNI boundary gate. A stale test-only
JNI function table had omitted GetStringUTFLength; the fixture now supplies both
length callbacks from its actual ASCII data and asserts the UTF-length call while
retaining allocation-failure/release checks. Production JNI code was not changed.
See reports/cloud-optional-host-gates.json. This does not substitute for Android
instrumentation, phone GPU performance, or physical scan-quality evidence.

## Durable admitted-camera registration increment (06:30 UTC)

The next candidate uses the existing bounded atomic frontier store with real photo
IDs and sparse-stage identity. Exact source-feature/lens identity, original ordered
two-anchor gauge and overlapping accepted poses must agree before reuse. Recovered
and newly accepted poses merge by photo ID rather than list position; stale,
corrupt or conflicting frontiers cannot replace fresh geometry. Failed optional
checkpoint writes are explicitly non-durable. Cancellation propagates, and the
frontier is never advertised as completed alignment or complete track coverage.

All>=3-photo captures use this path, including already registered sets; two-photo
captures make no frontier claim. The pair budget remains at most66 and production
admission stays2–12. Finalization still rebuilds the full accepted track graph.
The changed registration policy has a new sparse-stage identity.

Independent tests passed2 JVM cases and the extended existing real host-JNI
six-photo limited-overlap fixture. Cancellation after the third saved camera,
fresh-store reload with reordered input/new token, and exact pose preservation
produce5 registered cameras and1 correctly disconnected camera. The rebuilt full
track graph then finalizes210 tracks/630 original observations. Complete-set
persistence is separately checked with native callbacks forbidden. Full drafted
pipeline direct Kotlin compilation passes; actual integrated Gradle/device gates
remain pending. See reports/cloud-registration-resume.json. No physical-photo or
handset qualification is implied.

### CI cancellation repair

The candidate-evidence job now uses `!cancelled()` rather than unconditional
`always()`. Failed native/Android/device jobs still reach the evidence audit, whose
all-jobs-success assertion and exact-artifact verifier remain unchanged. Cancelled
superseded runs no longer intentionally retain their finalizer/concurrency slot.
Existing queued runs retain their original workflow and need separate cancellation;
changing source does not retroactively release them. The focused workflow contract
and adversarial evidence-validation suite pass57 tests.

The integrated registration increment now passes the actual full Gradle debug
validation run:664/664 JVM tests without failures or skips, Android lint without
Error/Fatal issues, and AndroidTest Kotlin compilation.54 source/runbook contracts
and the source manifest pass. This is not actual Android registration-resume
execution; that gate remains open. The separate optimized APK instrumentation
harness still has cross-APK optimizer linkage failures and is being addressed with
explicit, bounded QA retention/linkage checking rather than repeated speculative
keeps or disabled minification.
