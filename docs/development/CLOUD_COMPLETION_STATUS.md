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

## Android test dependency ABI alignment (07:04 UTC)

Original-bytecode analysis found a real test dependency mismatch: Espresso3.5.0
called an AndroidX Test ViewCapture method removed from the resolved1.6-generation
API. Espresso core/idling now explicitly use3.6.1, matching runner1.6.2/ext-junit1.2.1;
the missing ViewCapture call no longer appears in tracing. The already-reviewed
error_prone_annotations2.26.1 pin is test runtime rather than compile-only to avoid
consistent-resolution mismatch. These are AndroidTest-only changes: no application
runtime configuration or other dependency version changed. Obsolete test-only
transitive memberships were removed; new artifacts were independently verified
against official repository bytes/published checksums. Both debug and release
instrumented-test compilation/classpath export pass. Runtime linkage/device
qualification still requires the separately reviewed QA-retention build.

## Explicit QA instrumentation linkage gate (07:42 UTC)

The latest published source is `4f5307d949d5f8aba25520bc03b1455e84c4cdad`.
An opt-in, test-signed release QA path is under validation. It derives a finite
cross-APK retention surface from original compiled inputs and checks the frozen
app/test APK pair using the pinned R8 tool, with source/input/rule hashes and
reviewed framework-only compatibility exclusions. It does not disable application
minification, change release security flags, or establish equivalence with the
normal shipping APK. See `INSTRUMENTATION_QA_RETENTION.md` for the exact boundary.

Successful assembly and package audit have not yet established final linkage:
the current frozen pair fails on two AndroidX anonymous-class EnclosingMethod
metadata references after R8 lambda rewriting. Exact member retention rules were
consumed but did not repair the references. No additional AndroidX exception was
added, no success receipt was emitted, and this pair has not been installed or
executed. A narrowly scoped QA-test input metadata normalization is being tested;
its outcome must be recorded separately, not inferred from the proposed fix.

The previously observed release runner crashes executed zero tests. The earlier
debug smoke results remain limited to their recorded cases and do not substitute
for release instrumentation, complete scan-session execution, physical-device
accuracy/thermal testing, or production signing. GitHub verification remains
queued/pending; source publication is not evidence of passing remote CI.

A controlled replay using the same four original monitor classes and pinned
R8 8.11.18 reproduces exactly the two missing metadata methods in release mode
with the AndroidTest no-shrinking/no-optimization configuration; debug mode passes
that isolated reference check. This identifies a compiler-mode cause, not an
application runtime pass. The proposed input-only normalization follows the
[JVM EnclosingMethod specification](https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-4.html#jvms-4.7.7)
and [JDK-8348584](https://bugs.openjdk.org/browse/JDK-8348584): anonymous classes
within static field initializer lambdas retain the enclosing class but have zero
method_index. The workaround is custom and QA-only, not an official R8 patch.

### QA normalization integration checkpoint (08:01 UTC)

The metadata-only repair now passes the isolated release reference check with no
missing-reference diagnostics. The integrated focused suite passes89 tests and3
subtests; the duplicate-ZIP warning comes from an intentional rejection fixture.
Independent review confirmed the exact two original class hashes, unchanged
executable bytes, deterministic complete-output reconstruction and original
class-set comparison. Only the exact Java9 JPMS module descriptor path is omitted,
with every original descriptor hash inventoried; other META-INF classfiles reject.
Apache-2.0 attribution accompanies the embedded AndroidX regression class fixtures.

Actual Gradle QA-mode classpath export succeeds using the public AndroidTest
ScopedArtifact transform. The application component is not transformed. Both
application/test shrink and package tasks are wired to verify hash-bound receipts;
actual negative guard runs, regenerated rules and full paired-APK linkage remain
pending at this checkpoint. No emulator results are implied by these host checks.

The actual negative Gradle gates now pass: missing manifest blocks the application
minify and test-package entrypoints at verifyQaInstrumentationRetention; a stale
source fingerprint blocks test minify at the same verifier before shrinking.
The original generated manifest was restored and verifies successfully. Invalid
QA flags, debug-test mode and production-signing combinations reject; normal
QA-disabled assembly's dry-run includes neither normalization nor QA verification.
Fresh schema2 generation passes with1,067 retained types and the same finite rule
hash; actual scoped transformation records22 inputs and3 exact JPMS exclusions.
Full rebuilt APK-pair linkage and Android execution are still pending.

### Normalized QA packaged-pair static gate (08:09 UTC)

Published source `8f7bba29d3126219115fb49ecfa215e03fa92c11` (tree
`9a8558aaacd83ad85842271b650b59418a284571`) now builds the coherent QA pair
and passes packaged-pair static linkage with **PASS_WITH_DOCUMENTED_EXCLUSIONS**:
13 precisely reviewed diagnostics, no unexpected missing references. The two
stale lambda EnclosingMethod references no longer appear. This does not mean a
zero-missing graph or device-safety qualification. Portable hashes and the full
exclusion inventory are in [the QA report](../../reports/cloud-instrumentation-qa.json).

The frozen application SHA-256 is
`47568597b5525498e2f9cfe9f65b7572e8bd411793513fd87c20551c296af806`;
the instrumentation APK SHA-256 is
`7a670c4f855489f3748e3df5119dbe3bb118b762b6d295ba4430959a243b5a78`.
These are explicitly QA-retained artifacts, not equivalent to the normal shipping
APK. Application optimization remains enabled. Only two pinned AndroidTest
classfile EnclosingMethod method_index fields change from `000c` to `0000`;
executable bytes and enclosing class owners remain unchanged. The actual scoped
input merge inventories three exact Java9 module descriptor exclusions.

The 89 focused host tests plus three subtests, real normalized Gradle export,
missing/stale app/test shrink/package rejection gates and coherent optimized
assembly pass. Static platform resolution uses compileSDK36 public APIs plus
actual API35 hidden Instrumentation declarations; it is not API35 runtime
availability proof. Exact-pair Android execution is **NOT_RUN / pending** at this
checkpoint. Physical quality/resource evidence, full journey qualification and
production signing remain open.

### Exact QA pair Android runtime checkpoint (08:29 UTC)

The same frozen pair now executes on the official API35 `aosp_atd` x86_64
revision1 image: emulator37.2.12, two logical CPUs, 2048 MiB RAM, SwiftShader,
no KVM acceleration and no snapshot. Eight targeted tests pass: runner/JNI1,
native canonical-path OBJ/STL/cancellation export1, original-failure-preserving
cleanup1, camera poses2, fenced commands1 and durable checkpoint/lease recovery2.
The earlier Trace/LazyKt startup failures do not recur on this exact QA pair.

The full synthetic import/reconstruct/reopen/export session **fails**: its
unchanged180-second operation deadline expires while verifying overlapping
photo pairs (0/3). Instrumentation reports205.824 seconds and one failure. The
original assertion is preserved rather than masked by cleanup. This does not
establish complete-session or export-journey success; the cause remains unresolved
and must not be attributed solely to emulator speed without further evidence.

Before any tests, an initial guest network-stack/system-server restart disrupted
installation. A bounded staged installation retry after system-server recovery
succeeded for both APKs. This infrastructure incident is recorded separately
from the later application pipeline timeout. The portable QA report includes
individual test methods, counts, timings and evidence-log hashes. No normal
shipping APK, physical handset, geometry/texture quality or production-signing
qualification follows from these targeted passes.

## Pair-verification diagnostics and exact work reduction (08:38 UTC)

The last actual full-session failure identified only pair0/3, a label emitted
before feature-cache loading, descriptor matching and native geometry. It does not
prove which substage consumed the deadline or establish a deadlock. New bounded
ordinal-only snapshots distinguish load/cache outcomes, descriptor counts,
forward/reverse matching blocks, native-wrapper entry and cancellation/cleanup.
No filenames or photo IDs are included. Durations are sampled at boundaries,
not a continuously ticking clock; synchronous OpenCV blocks remain noninterruptible.
Diagnostics preserve original failures and parent cancellation, and the existing
180-second session deadline is unchanged.

The exact reciprocal matcher now searches forward first and reverse-queries only
second-image descriptors named by a forward strict-ratio survivor. Both directions
still search their full training descriptor sets, use the same L2 distance and
strict ratio, and return the original ascending first-image identities. Omitted
reverse queries cannot survive reciprocity. Bounded gathered matrices/views are
released on cancellation/failure; old positional and trailing cancellation call
forms remain supported. Cache/settings identity is unchanged because output
semantics are unchanged, not because quality was reduced.

Host model differential checks pass530 comparisons across random/unequal/empty,
duplicate/tied descriptors, strict-ratio boundaries and five block sizes. On the
three labeled synthetic fixtures, distance work falls30.6–36.8%. These Python/OpenCV
host timings exclude Kotlin per-row JNI gathering overhead and are not Android
latency evidence. See reports/cloud-sparse-reverse-host.json and reproducible
`tools/check_sparse_reverse_matching.py`. Direct timing JVM tests pass12 cases;
focused source contracts pass3; root's combined host QA selection passes70 tests
and3 subtests. Actual Gradle and new actual-Kotlin Android differential checks
remain pending, and must precede another complete-session attempt.

The integrated increment now passes actual Gradle validation:676 JVM tests with
zero failures/errors/skips, lint with zero Error/Fatal issues, and AndroidTest
Kotlin compilation. The exact-candidate QA APK rebuild and actual Android matcher
differential/cancellation/session gates remain pending; host speed is not promoted
to a device-performance claim.

### Pair diagnostics and exact sparse-reverse candidate (08:48 UTC)

Published source `9e1ad7dd7ffb4ea11e74a9c9d4f1ad541c6b8bcb` adds bounded
pair-phase/cache/descriptor/block timings and cancellation diagnostics without
private identifiers. The matcher retains exact reciprocal L2/strict-ratio
semantics and full train sets, but skips reverse queries not selected by any
forward ratio survivor. No feature budget or180-second session deadline changes.
Host model differential checks pass530 comparisons; their timing excludes Kotlin
row-gather JNI overhead and is not Android speed evidence.

Actual Gradle passes676 JVM tests (including12 pair-timing tests), lint and
AndroidTest compilation. The rebuilt QA pair passes static linkage with the same
13 documented exclusions and no unexpected references; its generated retention
surface contains1,069 types. New artifact hashes/provenance are kept separately
in [the pair-work report](../../reports/cloud-instrumentation-pair-work.json),
not substituted into the previous eight-pass/one-failure receipt. The new pair's
actual Android matcher differential and unchanged full-session checks remain
**NOT_RUN / pending** at this checkpoint. The prior timeout is not yet proven
resolved. QA/nonshipping and compileSDK36/API35 static-platform limits still apply.

### New-pair emulator infrastructure checkpoint (09:06 UTC)

The new pair has executed zero tests on API35: repeated system-server restarts
continued during a bounded600-second readiness probe without installation load.
This is recorded as **BLOCKED_INFRASTRUCTURE**, not an application test failure
or a regression of the previous candidate's API35 eight-pass/one-failure record.
A supplementary run of the same immutable APK pair on the official API30
AOSP/default x86_64 revision11 image is authorized and remains **NOT_RUN**.
It cannot substitute for API35 qualification. No source, APK, deadline or
matching semantics are changed for this environment-only retry.

Broad host validation of source `9e1ad7dd` additionally passes1,046 pytest cases
and three subtests in137.91 seconds; three optional gates are skipped in that
run and the source-manifest check is deselected because it is verified separately
at commit. All three skipped optional gates then pass with their configured real
dependencies in15.39 seconds. The duplicate-ZIP warning is the intentional
rejection fixture. These host results do not close either Android runtime gate.

## One-time Verify concurrency-group migration (09:32 UTC)

The old run39552262898 still holds the original Verify concurrency group with its
pre-fix unconditional evidence finalizer queued and no assigned runner. New Verify
runs remain pending with no jobs; independent export/native workflows also remain
queued, so a separate runner-scheduling delay of unknown cause is still observable.

Verify now uses one fixed `verify-v2-<pull-request-or-ref>` group. This removes the
known obsolete-group dependency for future runs while preserving per-ref
supersession, cancel-in-progress, all three required jobs, the cancellable evidence
finalizer and its exact-artifact/all-jobs-success acceptance checks. No permissions,
runner labels, timeouts or test gates change. The key is not per-SHA/run; repeated
migration is not an automatic retry strategy. This does not cancel the old run or
prove a runner will start. The interpretation follows GitHub's documented
[concurrency-group behavior](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
The updated cancellation/workflow and adversarial evidence suite passes58 tests.

### Supplementary API30 matching checkpoint and endpoint fix (09:35 UTC)

For source `9e1ad7dd` and its unchanged APK pair, the complete FeatureMatcherTest
class passes2 tests in287.353 seconds. SparseReverseMatcherDifferentialTest
passes its cancellation/ownership test but fails its exact-equivalence test
before comparison at the all-512f descriptor fixture:1 pass/1 failure in89.373
seconds. The full session is held, not passed. API35 remains infrastructure-blocked.

The failure exposes an existing validator endpoint error: OpenCV's CV_32F
exclusive upper bound rounds `Math.nextUp(512.0)` back to512f, rejecting the
valid inclusive endpoint already admitted by the cache contract. A real host
OpenCV reproduction confirms that the smallest Float successor widened to Double
admits512f but still rejects its next Float successor, negatives and nonfinite
values. The pending source fix changes only this bound and adds explicit endpoint
regressions; it does not remove the failing fixture or relax the intended domain.
The revised fix has not yet been built or qualified on Android. The published
API30 three-pass/one-failure evidence remains attached to its original candidate.

The endpoint correction passes the actual full debug gate:677 JVM tests with
zero failures/errors/skips, AndroidTest Kotlin compilation, and lint with zero
Error/Fatal issues. The persisted real OpenCV host regression passes all eight
boundary cases; the focused Python file passes5 tests. Previous candidate API30
results remain3 passes/1 endpoint failure; the corrected APK must be rebuilt and
run before claiming that device failure is fixed.

## Corrected endpoint candidate: actual API30 results (10:15 UTC)

The source67a071fa QA pair now passes all5 actual Android matcher tests:
FeatureMatcherTest3/3 in287.704s and SparseReverseMatcherDifferentialTest2/2
in93.129s. This includes the original512f fixture, exact endpoint-domain rejection,
photographic whole-BF equivalence/native geometry, varied descriptors/ties/order,
and cancellation/ownership/API regressions. See reports/cloud-instrumentation-endpoint.json
for exact APK and log hashes. These are supplementary API30 results, not API35 or
normal shipping APK qualification.

The unchanged180-second complete-session gate FAILS (total test241.866s), this time
while extracting features for photo2/3 in synchronous OpenCV, cacheHit=false.
The latest snapshot's elapsed495ms marks native-call entry; it is NOT the ongoing
native runtime. Pair verification had not begun (pairWork=null). The original
AssertionError is retained with no suppressed cleanup exception in the captured
log. No completed reconstruction, export journey or speed guarantee follows.

A separately labeled, explicit opt-in test-only diagnostic continuation is now
being implemented to collect bounded functional-completion evidence after a typed
deadline miss. The ordinary180-second gate must remain FAIL even if diagnostic
continuation finishes; default behavior, application deadlines and CI acceptance
are unchanged. No such diagnostic has executed at this checkpoint.

### Test-only functional continuation candidate (10:17 UTC)

Following the endpoint candidate's actual180-second deadline failure, a test-only
opt-in diagnostic is under validation. `rfDiagnosticContinuation` defaults off;
only the exact strings `true` and `false` are accepted. Only a typed failure from
the initial180-second reconstruction wait can trigger an additional600-second
wait. This780-second reconstruction-wait budget does not include imports,
functional assertions or cleanup, which retain their existing behavior and bounds.
No production timeout, feature budget or normal CI acceptance gate changes.

The original deadline assertion remains the final JUnit failure even if every
existing geometry, repository reopen, SAF byte-hash, original-preservation and
GPU/screenshot assertion subsequently completes. A distinct diagnostic completion
marker is emitted only after those assertions. Later failure or cancellation is
suppressed onto the original deadline; owned-worker cancel/join and leased cleanup
remain in place. A marker is not a passing180-second session or physical-quality
evidence. Exact source, APK hashes and explicit argument must accompany any run.

The pure Kotlin helper passes12 host JUnit tests, including default-off, strict
parsing, typed-deadline selection, original-failure identity and secondary-error
preservation. Seven Python diagnostic/pair contracts pass. Actual Gradle compile,
Android helper execution and any diagnostic continuation outcome are pending.

The corrected suspend-capable diagnostic helper now passes12 direct host JVM
regressions (including real suspension and close-failure suppression) and7 Python
contracts. Actual AndroidTest compilation and the full debug validation command
succeed, with zero lint Error/Fatal issues; unchanged application JVM results are
reused rather than described as newly executed tests. Diagnostic execution and its
new coherent APK-pair checks remain pending. The paused-emulator/1GiB-build
experiment did not preserve the guest: its process was killed, with cause not
proven from available memory data. Builds and live guests are separated again;
this experiment is not a memory-safety success.

### Diagnostic candidate infrastructure stop (10:52 UTC)

Published diagnostic source `b7efe9539bed66991a1738c48802ff7c82fb4077` compiles
after correcting the test-only suspend callback signatures. Its coherent QA pair
passes static linkage with13 documented exclusions; all17 application DEX/native
entries are byte-identical to the endpoint candidate, with both APK and per-entry
hashes inventoried. This is not complete-APK or shipping-build equivalence.

The fresh API30 guest repeatedly restarted its framework before stable installation
readiness. The bounded600-second gate ended NOT_STABLE (exit124): **zero installs,
zero helper tests and zero diagnostic session tests executed**. The supervisor
exited0 after requested console shutdown; no immediate relaunch is underway.
The diagnostic runtime gate is **BLOCKED_INFRASTRUCTURE**, and both helper Android
execution and functional continuation remain **NOT_RUN**. The previous endpoint
candidate's five actual matcher passes and one180-second full-session failure
remain unchanged. Closed evidence hashes and exact planned argument are in
[the diagnostic report](../../reports/cloud-instrumentation-diagnostic-continuation.json).

Closed-log investigation identifies the concrete restart chain: network-stack
TetheringService ANR (`framework-logcat.txt:9034–9037`), ActivityManager background
ANR kill (`9095–9097`), then ConnectivityModuleConnector's fatal
`IllegalStateException: Lost network stack` in system_server (`9159–9174`). The
same chain repeats in `live-logcat.txt:15572–15676` and `22176–22394`. In contrast,
the successful endpoint guest logs network-stack connection at line7830 and
SystemUI/phone/IME startup ANRs, but no matching lost-network-stack/system-process
fatal. The failed guest also briefly connected its network stack before a pending
service ANR killed it; connection/service enumeration alone is not readiness.

One host snapshot shows QEMU110% CPU,3,276,832KiB RSS and2,927,220KiB available
host memory; the first service-ANR memory-PSI snapshot is zero. No OOM-kill
signature was found. These observations do not prove a CPU/TCG or OOM root cause,
exclude transient pressure, or establish a watchdog deadlock.

The proposed next experiment changes only the supported virtual CPU count from
two to one, retaining the same API30 image,2048MiB RAM,SwiftShader and unaccelerated
cold-boot configuration. CPU count is an [official AVD configuration option](https://developer.android.com/studio/run/managing-avds);
[QEMU documents TCG vCPU synchronization](https://www.qemu.org/docs/master/devel/multi-thread-tcg.html),
but neither source proves one core will improve this guest. Preserve the existing
90-second stable-readiness window and600-second total budget; strengthen its
predicate with stable server/network-stack PIDs and no new critical service
timeout, ANR kill or system-process fatal. No watchdog/security/network-stack or
application timeout changes are proposed. Stop before installation on renewed
critical failure or budget expiry. This experiment is **PROPOSED / NOT_RUN**.

### Recovery visibility and notification-independent admission (11:01 UTC)

The library service query and local attention predicate now share the same states,
including `RECOVERY_REQUIRED`; deletion tombstones remain excluded. Reconstruction
submission precedes optional notification permission. Its result callback cannot
own or replay a submission, and existing project/revision/admission fences remain.
The processing destination still reads persisted jobs, not an assumed acceptance.

Actual focused JVM checks pass 21/21 and the unrestricted suite passes 690/690,
with no failures, errors or skips. AndroidTest Kotlin compilation succeeds; lint
has zero Error/Fatal findings (92 warnings and 3 hints remain). Independent static
review found no material blocker. Actual API33+ permission-dialog recreation,
delayed admission and double-tap scenarios remain NOT_RUN; helper tests are not
device-equivalence proof. No new APK was generated for this increment. See
[the source-hashed UI report](../../reports/cloud-ui-recovery-notification.json).

### Identity-fenced presentation recovery (11:14 UTC)

Saved selection now restores calibration and preview without advancing picker
selection generation. Direct-operation completion restarts read-only observation,
so duplicate/rejected-only imports no longer leave an unchanged result hidden.
Null or changed full result identities clear compatibility and typed presentation
before suspended reads; publication checks current selection, generation, revision,
result identity and state. Completed-job notifications use the same reader rather
than a competing raw JSON publisher. Recovery exceptions are contained, cancellation
propagates, and operation completion is marshalled to the ViewModel owner dispatcher.

Actual Gradle focused tests pass 12/12; the unrestricted JVM suite passes 702/702
with no failures, errors or skips. AndroidTest compilation includes five new
synthetic presentation regressions. Lint has zero Error/Fatal findings. Independent
review and a separate standalone test rerun pass. These five Android tests remain
NOT_RUN; direct SavedStateHandle fixtures are not OS process-kill evidence and no
reconstruction-quality claim follows. See the [source-hashed report](../../reports/cloud-presentation-recovery.json).

### Presentation recovery QA pair: static pass, one-core runtime blocked (11:36 UTC)

Published application source `055f988c` produced a coherent optimized QA-retained
pair: app `d413052c878f05712d70a577770d4212b6b8c18beb0bbd055484bb68f66165a9`,
test `b7802c0616233897bce02934d915aa3f804b454ba95b2fd589be5826aa45120a`.
The packaged static gate passes with exactly13 documented exclusions and1079
retained types; package validation passes. This is not normal shipping equivalence
or API30/35 runtime-availability proof. Actual host validation remains702 JVM
passes, including12 focused presentation tests, AndroidTest compilation and
zero Error/Fatal lint findings.

The authorized API30 one-core TCG experiment failed the unchanged90-second
stability/600-second overall readiness gate (last observation600.006s, exit124).
Boot completion first appeared at420.960s, but sequential service probes took
roughly42–56s and repeatedly exceeded the30-second sampling-gap guard. Observed
nonempty PIDs remained system_server550/networkstack1044; missing/null command
results do not prove process death or restart. Readiness records contained no
critical framework events; seven initial LOG_UNAVAILABLE observations correctly
failed closed until the capture appeared. A separately authorized single batched diagnostic also failed:
host deadline25.005s, only boot1 completed (2.96 guest seconds), next PID probe
incomplete. This did not establish responsive readiness or fix the issue.
No CPU/OOM causal claim follows. Guest closed at11:35:37 UTC, supervisor exit0.

Zero APKs were installed and zero tests ran: all five presentation device tests,
12 continuation helper tests, and the opt-in full session remain NOT_RUN. No
watchdog/security/default180-second deadline changes or further boot retry were
made. The standalone experimental batch probe and six parser tests are retained
for reproduction, explicitly NOT integrated into readiness or authorization to
install; the full batched gate draft was not promoted. Nine readiness parser tests
also pass. See [the exact artifact and closed-evidence report](../../reports/cloud-instrumentation-presentation-recovery.json).

### Capture source ownership and duplicate outcomes (11:53 UTC)

Only a confirmed newly copied private original authorizes best-effort CameraX
staging cleanup, after optional guidance and exact pending-photo ownership checks.
Generic publishers retain their previous ownership contract. Duplicates are now
explicit non-error outcomes without a count/revision advance or accepted-photo
guidance. Uncertain publication retains temporary bytes and blocks further capture
across lifecycle/checkpoint restoration. Cleanup failure cannot undo accepted save.

Focused actual Gradle tests pass 25/25; unrestricted JVM tests pass 716/716 with
no failures/errors/skips. AndroidTest compilation and lint pass; new nullable-file
test warnings were removed without production changes. CameraX/device checks and
APK regeneration remain NOT_RUN. Retained uncertain/duplicate files remain cache,
subject to OS eviction and existing age-prune; this is not durable recovery storage.
See [capture evidence](../../reports/cloud-capture-ownership.json).

Before this increment, source `427167ff` also passed 1,067 broad Python tests,
with three optional skips subsequently executed successfully (real JNI, Kotlin
parser and software Vulkan dispatch). See [host evidence](../../reports/cloud-post-ui-host.json);
that run is not mislabeled as execution of this later capture patch.

### Fail-closed duplicate-original integrity (12:00 UTC)

Both duplicate lookup paths now validate the owned existing original's regular-file
status, bounded length and SHA-256. Missing/corrupt originals produce a separate
integrity failure, not a false duplicate or fabricated journal-backed recovery.
No original is overwritten/deleted or missing directory recreated. Remaining batch
sources are not opened; capture sources are retained with the same cache limitations.
Messaging explicitly says no automatic repair occurred and offers new-project
import for known-good external originals, not a promise that reopening repairs it.

The lost-commit-reply branch also verifies destination bytes before removing its
receipt or returning success. Failed verification retains its genuine journal and
reports pending recovery. Actual focused Gradle tests pass 14/14 and unrestricted
JVM tests pass 722/722, with zero failures/errors/skips. Provider fault-injection
Android tests compile; lint has zero Error/Fatal findings. Provider device tests
remain NOT_RUN, and host verifier tests do not execute Room. See
[the integrity report](../../reports/cloud-original-integrity.json).

### Normal optimized engineering APK delivered (12:09 UTC)

Source `08abcefc` now builds a normal optimized release APK with
`realityForgeInstrumentationQa=false` and existing test signing. Dry-run and actual
build contain no QA retention/generation tasks; the final R8 configuration excludes
generated QA rules. Strict offline assembly succeeds in 2m53s. Package signature,
permissions/offline policy, backup exclusions and 16KB ZIP/16-native-library checks
pass. APK SHA-256 is `aa2312324619dae5699d0f2e5364cacf94c83f41cc7facd55bfa6f2dc9a6f84d`.

The 106,220,465-byte APK was privately delivered in three verified split ZIP parts
with assembly instructions. Its package, version code and test certificate match
the retained earlier debug preview; in-place update is expected but NOT TESTED.
Do not uninstall an existing app or delete scans. This is an engineering candidate,
not production-signed, installed, device-qualified or a passing full-session build.
See [normal release evidence](../../reports/cloud-normal-release-engineering.json)
and [download instructions](ENGINEERING_APK_08ABCEFC.md). Binary APKs remain outside
Git source history.

### Real Room/import HOST integration gate (12:39 UTC)

A portable, explicit `:app:hostIntegrationTest` now runs Android35 framework code
under Robolectric4.14.1 with real generated Room DAOs and native host AOSP SQLite.
The pinned framework resolves through strict Gradle verification and its own locked,
nontransitive configuration; Robolectric's internal resolver is always offline.
Source and staged framework bytes are rehashed, with one fork/1GiB/five-minute task
bounds. Ordinary unit tests exclude the dedicated HOST package and do not execute
or resolve this SDK. CI explicitly runs and collects both gates separately.

Actual HOST tests pass 6/6, covering database reopen persistence, original bytes,
duplicates, publication failure/readback/recovery, synthetic cancellation exception,
and missing/corrupt originals. Ordinary JVM tests independently pass 722/722;
AndroidTest compilation and lint pass, with zero Error/Fatal lint findings. Another
86 Python wiring/CI/package checks pass. Missing/tampered staged SDK and misplaced
HOST sources were actually rejected and restored. Release dependency inventory is
byte-identical before/after; no prior verification hash was changed or removed.

These tests deliberately use synthetic input bytes and inspector metadata. They
are NOT camera/decoder, repository-facade/storage-budget, Binder permission, app
JNI/GPU, physical-device, power-loss or release qualification. Synthetic thrown
cancellation is not yet an actual structured Job cancellation test. See
[HOST evidence](../../reports/cloud-host-room-integration.json) and
[portable instructions](HOST_ROOM_INTEGRATION.md). The privately delivered APK
instructions now also include built-in macOS/Linux assembly, in addition to Windows.

### Actual caller cancellation after Room commit (12:48 UTC)

The separate HOST gate now passes 7/7 tests. A new structured async Job is cancelled
only after real Room commit is observed; a controlled reply barrier permits the
production NonCancellable publication section to finish. The cancelled caller does
not receive a successful result, and timeout is explicitly rejected as cancellation
evidence. After worker cleanup, database reopen confirms the committed row, exact
original bytes/hash, clean staging/journal and idempotent recovery. The duplicate
integrity test also covers a missing originals directory without recreating it.

Ordinary JVM tests remain 722/722 passing; AndroidTest compilation is current and
lint passes. This is actual HOST coroutine cancellation, not process death, device
or power-loss durability. Inner waits/joins are bounded, with method120s/task5min
infrastructure bounds retained. No production code or release dependency changed.
See [cancellation evidence](../../reports/cloud-host-commit-cancellation.json).

### Full regression and exact test-only dependency correction (13:10 UTC)

The broader run exposed one outdated assertion: Robolectric4.14.1's verified
runtime metadata adds Espresso idling-resource3.6.1 to unit-test runtime, while the
older check allowed AndroidTest only. The correction allows exactly those two unit
runtime memberships for idling-resource, not espresso-core, unit compile or APK
scopes. Annotation versions remain separately constrained, and all Robolectric
locked entries are explicitly forbidden from APK/instrumentation configurations.
No production dependency or behavior changed.

After independent review, the full Python suite passes 1,072 tests (three optional
skips), plus three subtests. All three optional native/JNI, parser and software
Vulkan gates then execute and pass. These are host/static tests, not device or
phone-GPU qualification. The prior failure and corrected evidence are retained in
[the dependency-contract report](../../reports/cloud-test-dependency-contract.json).

### Portable production-wrapper native integration (13:25 UTC)

A fresh portable compile/run passes exactly three new HOST JNI tests: real native
registration/triangulation and typed bundle adjustment through production wrappers,
offside final-sparse publication preserving anchors, progress cleanup/quiescence,
actual owner cancellation rejecting a subsequent native call, and exact callback
error propagation without publication. Source, native inputs, dependencies, compiled
classes and runtime receipts are hash-bound before and after execution.

The full Python regression passes 1,081 tests plus six subtests; all three optional
native/parser/software-Vulkan checks also execute and pass separately. The portable
helper's nine tests are included in the regular suite. No app production code changed.

This is synthetic host glibc/JDK evidence, NOT Android ABI/ART, in-flight kernel
interruption, photo decode/OpenCV, full SparsePipeline, physical scan quality or
release qualification. Observed hashes are not native build attestation. See the
[native-wrapper report](../../reports/cloud-native-wrapper-integration.json) and
[portable harness instructions](../../tools/native_wrapper_validation/README.md).

### Callback cancellation cannot publish a completed native result (13:40 UTC)

A real JNI regression reproduced a production bug: a progress reporter child could
throw CancellationException, cancel its native lease, yet let its parent stage
return success. The narrow fix propagates callback cancellation to the owning stage
before teardown, including callback self-cancel-and-return, without treating normal
reporter shutdown as failure. Existing finished-state boundary remains explicit.

Baseline: four tests, one assertion failure (success instead of cancellation).
Fixed and freshly integrated: seven native-wrapper tests pass, preserving exact
cancellation/native-error identity, preventing publication and allowing normal
shutdown followed by reuse of the same lease. Ordinary JVM722, HOST Room7,
AndroidTest compilation and lint pass. Python1,081 plus six subtests and all three
optional native/parser/software-Vulkan checks pass. An initial manifest-only failure
was corrected by regenerating checksums and rerunning the complete suite.

These are host synthetic observations and subsequent-call token checks, not
in-flight native interruption or Android end-to-end success. The delivered APK
predates this source fix; no replacement APK/device qualification is implied.
See [red/green and integrated evidence](../../reports/cloud-native-callback-cancellation.json).

### Reconstruction cancellation releases native ownership (13:53 UTC)

The controller previously joined its heartbeat in an already-cancelled context,
which could skip native token finalization and running/progress cleanup. A storage
release failure could likewise bypass the tail. The extracted old ordering fails a
single real-token regression; the identical probe passes with the production helper.

The new helper surrounds existing body/handlers, performs cleanup non-cancellably,
joins heartbeat before releasing token/reservation, preserves actual escaping errors
and still attempts independent cleanup after a cleanup error. Already handled job
failures remain handled. A genuinely live heartbeat after failed join retains
ownership rather than permitting unsafe release. Native finish precedes atomic
handle clearing. No automatic recovery from genuine cleanup failure is claimed.

Fresh shared HOST execution passes13 tests, including24 cancelled-owner real-token
cycles beyond the16-live-operation registry limit. This is helper runtime evidence,
not a full Android controller execution. Three structural checks and integrated
Android compilation cover controller wiring. JVM722 and HOST Room7 pass; lint passes
on a separate retry after the first Gradle daemon disappeared for unknown reasons.
The full Python suite passes1,084 plus six subtests, and all three optional host
checks execute successfully. No emulator was started.

See [cleanup evidence](../../reports/cloud-reconstruction-cleanup.json). The delivered
APK predates these source fixes; an updated normal engineering build follows.

### Updated engineering APK delivered (14:00 UTC)

Normal optimized release build from442c97ec now contains callback cancellation and
native-operation cleanup fixes. QA retention is OFF; test signing remains explicit.
Build completed in2m28s; signature, offline permissions, backup exclusion and16KB
ZIP/all16 native ELF checks pass. APK SHA256 is
`705ea127088b8a0186235aa20c39d0adce1bcad5ee7124c1dac1fd47a2098725`
(106,220,465 bytes). Three verified split ZIP parts and Windows/macOS/Linux assembly
instructions were delivered privately. No binary is committed to source.

Package/version19/test certificate match the prior engineering build, but in-place
installation/data migration remain untested. Do not uninstall or delete existing
scans. This is not a production-signed or device-qualified release. See
[APK evidence](../../reports/cloud-cleanup-engineering-apk.json) and
[assembly instructions](ENGINEERING_APK_442C97EC.md).

### Actual controller/Room admission and value-aware running cleanup (14:18 UTC)

Production enqueue now rejects noncanonical operation UUIDs before recovery/queue
writes. The generic durable job store still accepts its existing safe-ID contract.
Persisted execution-incompatible IDs now enter the controller's handled cleanup
boundary and settle to FAILED/INVALID_INPUT without leaving a RUNNING lease.

Actual HOST controller/Room tests exposed an additional bug: boxed inline JobId
values were compared using reference-identity CAS, so equal job IDs did not clear
the running marker. Value-aware atomic update fixes this while retaining a different
replacement identity. The initial failing null assertion remains unchanged.
Focused red2/1 (admission fix already present) became green3/0. The third test
deliberately injects a replacement identity; it does not claim concurrent workers.

Full gates pass: ordinary JVM722, HOST controller/Room/import10, AndroidTest
compilation and lint. Python1,086 plus six subtests pass, and all three optional
host native/parser/software-Vulkan checks execute successfully. These tests use
actual controller/Room behavior but stop before photo decoding/native reconstruction;
canonical enqueue service behavior is shadowed, not device-qualified.

See [controller admission evidence](../../reports/cloud-controller-admission.json).
The privately delivered APK from442c97ec predates this fix; no new APK runtime
claim is implied.

### Engineering APK includes admission/identity fixes (14:24 UTC)

The latest private engineering APK is built froma1806643 and supersedes the442c97ec
download. Normal optimization, QA retention OFF and test signing remain unchanged.
Build2m29s and signature/offline/backup/16KB ZIP/all16ELF checks pass. APK SHA256:
`cee7ccd79c26548875d9a50a3bc808ab34676f15e1adf4378f8ce5bb3c1f9ef6`
(106,220,465 bytes). Verified three-part ZIP and cross-platform assembly instructions
were delivered privately; binaries are not in Git source.

This includes the value-aware running-job cleanup correction and incompatible
request admission handling. Package/version19/test certificate are unchanged, but
installation/data migration and complete scan-to-export remain untested. See
[APK evidence](../../reports/cloud-admission-engineering-apk.json) and
[assembly instructions](ENGINEERING_APK_A1806643.md).

### Exact-version production matcher HOST JNI gate (14:48 UTC)

Official OpenCV4.12 source was pinned and built outside the repository with only
core/imgproc/features2d/java. The app's unchanged AAR Java classes, not the generated
Linux jar, loaded the exact matching native library. All361 matcher-relevant native
declarations matched/exported; real Mat/BF smoke passed. A fail-closed hook rejected
an initial implicit ADE download before fetch; successful configuration fetched no
dependencies. Source/build/symbol/receipt hashes are preserved.

Current production matcher/frame/lens sources compile afresh without cached app
classes. Eight HOST native tests pass, including whole-pair BF differential ordered
payload across unequal descriptors/block sizes, reverse-row remapping, ratio/tie/
range boundaries, cancellation/observer identity and descriptor reuse. The shared
portable helper was freshly rerun with pre/post input/output hashes and exact native
mappings. Its12 orchestration tests pass. Broad Python1,098 plus six subtests pass;
all three optional host gates also execute and pass. No app production code changed.

This is synthetic-descriptor Linux JNI coverage, not Android feature extraction,
Bitmap decode, full pipeline, physical photo quality or device performance. Scanner
geometry JNI is not loaded here; verification only tests its small-input rejection.
Source hashes are observed provenance, not an independent publisher attestation,
and system library dependencies are not fully hermetic. See
[matcher evidence](../../reports/cloud-production-matcher-host.json) and
[portable instructions](../../tools/feature_matcher_validation/README.md).

### Shared production SIFT core and adapter compilation (15:02 UTC)

The Android extractor now delegates its existing SIFT/frame policy to an internal
borrowed-gray core. Public synchronization/signatures, cache identity, parameters,
keypoint order, color sampling, clone ownership and cancellation cadence remain
unchanged. Android decoding/projection/color conversion and bitmap lifetime stay
in the adapter. Working Mat allocation timing changes are explicitly documented.

Separate37-declaration SIFT linkage proof extends the immutable OpenCV capability.
Fresh shared native suites pass8 SIFT and8 matcher tests, using exact AAR classes,
synthetic Mats, bounded execution and pre/post input/output hashes.22 new helper
guard tests pass. Android adapter/AndroidTest compilation, ordinary JVM722,
HOST Room/controller10 and lint pass. Broad Python1,120 plus six subtests and all
three optional host checks pass.

This does not qualify Android decoding/EXIF/color behavior, full reconstruction or
physical scan quality. Existing FINISHED-observer exception masking is intentionally
unchanged for this refactor; a separate regression/fix is pending. The delivered
APK predates this source change. See [SIFT evidence](../../reports/cloud-production-sift-host.json)
and [behavioral boundaries](SIFT_EXTRACTION_BOUNDARY.md).

### Preserve native SIFT errors when observation also fails (15:14 UTC)

A separate actual-JNI regression reproduced FINISHED observer masking: deliberately
emptying the borrowed Mat in STARTED triggers SIFT's explicit empty-image native
error, but a second observer exception previously replaced it. Baseline10 tests
had1 failure; the narrow fix passes10/10 and attaches the observer error as
suppressed while retaining native CvException/diagnostic. STARTED failure and
successful-kernel observer-failure semantics, cancellation and cache identity stay
unchanged. This is labeled invalid-owner fault injection, not normal caller use.

Fresh shared SIFT10 and matcher8 native tests pass. Ordinary JVM722, HOST
Room/controller10, AndroidTest compilation and lint pass. Broad Python1,120 plus
six subtests and all three optional host gates pass. The broad run used a reviewed
private observer for exact public dependency-audit fixtures, without argument/result
or assertion changes; diagnostic contents are not published.

No Android runtime/full scan or physical quality qualification is implied. See
[separate red/green evidence](../../reports/cloud-sift-observer-primary.json). The
previously delivered engineering APK predates this correction.
