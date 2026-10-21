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
