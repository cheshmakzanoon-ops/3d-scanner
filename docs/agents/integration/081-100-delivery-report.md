# Agents081–100 delivery and release acceptance

Interim historical record of frozen86. Its debug attribution result was
invalidated when six packaged debug-only modules were found missing from the
release-only notice index. Agent095 has supplied a separate debug inventory and
notices. The corrected provider and the next candidate are under validation;
final machine records and device outcomes will identify their actual source.

The requested20 role implementations are committed and pushed on their assigned
branches. Agent100 integrates reviewed owner deliveries on
`agents/100-integration-lead`. Main remains at the campaign base,
`ad3029193f304c70931b94ea7a0df90b69d84b07`.

**The release is not qualified.** Required sanitizer and texture checks fail;
the full001–080 product cohort, physical/reference matrix, production signing
and external-beta evidence remain incomplete. A pushed branch is not a completed
acceptance gate. See `validation.json`, `branch-index.json`,
`candidate-qualification.json` and each numbered status receipt for exact evidence.

## Tested source and artifact identities

The frozen combined candidate is
`86df647bcdba4b110ad36dfb6ce572adb4261462`, tested from a clean detached checkout.
Later campaign-tool, receipt and report commits are separately identified and do
not change the provenance of its binaries.

| Artifact | SHA256 |
| --- | --- |
|Debug app APK|`3b747cd8d568e8c64fad1993033089a30b006998337c5225d8db587d613c2f58`|
|Debug test APK|`4224aa71f0e3d4b7507a54092ca18fd20537b99d57bb3e3f3416ddea2ae4c523`|
|Minified release APK, separate004 overlay|`ea454c8336211858deb580c2f9508fd458fb9f40fa34c9fef21ee99611908808`|
|Release AAB, separate004 overlay|`779f51d5f79cae3cad714d2f36fe141397e190dd845e1317023fd804824bab26`|
|Release test APK, separate004 overlay|`724a37e11c55d3ba8eb9c0d9cd31e0e13927eebdeec9b50442e9fa3c01b9cbef`|
|Exact frozen-candidate source archive, both copies|`31af56220d6fbe5c5696f983f4f1d35554a0766ea28ca8a7219efd6377bf384c`|

Release source is the separately tested temporary commit
`0a4d910dce4f527d0cf4e1e98b96a33bd9a55ab4`: frozen86 plus Agent091's reviewed
convention in004's module build file and its approved module lock, with the
mechanical manifest regenerated. Patch SHA256 is
`39425caae7039b45ce84542caf9dc57dccfe071a2fdeb7d5289efa58be9b8792`.
The minimal immutable004 follow-up `99eb293423560e8bcd9bacbc8e82cb33fc7634bf`
has since supplied this wiring and persistent normal-configuration locks; it is
integrated for the next direct build. The dry-run signing key is
not the production identity. App and test certificates agree.

## Actual validation

| Check | Result | Scope and limitation |
| --- | --- | --- |
|GCC and Clang Release|PASS|764 pytest tests and11 CTest tests pass under each compiler; existing numerical, bundle and licensed stereo benchmark checks pass.|
|GCC and Clang ASan/UBSan|FAIL|11 native CTest tests pass per compiler. Each Python suite has740 passes and24 failures; all eight Python-hosted commands exit nonzero with leak reports. Leak detection remains enabled.|
|Cross-compiler numerical parity|PASS|All three comparisons against GCC Release pass within the frozen tolerances. This does not waive sanitizer failures.|
|Native parser fuzzing|PASS|Three actual C-ABI libFuzzer targets complete10,000 iterations each with ASan/UBSan and source/compiler/library/binary fingerprints.|
|Debug Android compile/JVM/lint|PASS|Actual app and test APKs,35 JVM tests, lint,12 ELF/ZIP16KB checks, and packaged privacy pass on frozen86. The old attribution claim is invalid: six debug-only packaged modules lacked notices; corrected129-module debug attribution requires a new build.|
|Minified release packaging|PASS, separate overlay|Actual R8 APK/AAB/test APK,35 JVM tests, lint and85 generated universal/split APK signature/privacy/alignment checks pass. Both APK and AAB match123 runtime modules,92 notice texts and10 dependency-native records.|
|Packaged privacy|PASS|All nine storage domains are excluded in the actual app/universal package policies, including device-protected storage. Runtime offline evidence is separate.|
|Python host source and approved lock security|PASS|Actual Python host-tool scanners (178 files) and all14/15/31-distribution approved lock graphs pass. Reviewed path/line/rule/source/context exceptions are explicit and expiring; active findings are zero. This does not certify C++/JNI/Android security.|
|Legacy004 dependency input on frozen86|FAIL, historical|Its pytest8.4.1 direct and included requirements trigger PYSEC-2026-1845. Minimal004 follow-up99eb293 updates the input to9.0.3; the next candidate requires actual fresh direct and included audits. No advisory suppression.|
|Texture baseline, exact86 native library|FAIL|Independent gate finds23 UV-overlap pairs, area0.0007389108195187087. Historical benchmark PASS remains separate. Other measured anisotropy/seam/support metrics pass; no held-out or physical pass.|
|Independent export readers|PARTIAL / FAIL|Real Trimesh/Khronos/Blender roundtrips and provider tests execute. The supplied064 PLY fixture fails normal/winding quality; this alone does not prove a serializer defect.|
|Source reproducibility|PASS|Two canonical, manifest-verified2258961-byte archives of frozen86 are byte-identical and independently verified against that Git tree.|
|Campaign tooling|PASS|33 focused tests cover100 assignments, ownership, rename/deletion evasion, typed receipts including standard YAML lists, stale DONE evidence and staged/unstaged post-check mutations.|

The matching sanitizer-environment diagnostic reproduces leaks without loading
the reconstruction library: bare Python leaks52710 bytes/1019 allocations under
both runtimes; NumPy-only leaks287555 bytes/5335 allocations with GCC and287333
bytes/5331 with Clang. This explains an environment contribution, not every
individual failure, and does not turn the aggregate green. The separate default
Python allocator experiment has different results and is retained separately.

Device compatibility, supplemental JNI/provider tests, external recovery,
the099 synthetic installed journey and089 offline/minified checks are recorded
in the final machine evidence with their actual terminal outcomes. Emulator
observations do not qualify physical ARM64, camera, thermal, power-loss or
full-product journey gates. Six specialist gates remain excluded from the generic
debug compatibility scope and cannot inherit its outcome.

## Scope, remote history and unresolved work

All100 plan slots and committed outgoing handoffs are indexed by immutable remote
source. The index records ownership and typed-receipt failures as well as absent
or incomplete assignments; its request count is discovery, not an unresolved-work
total. Required001–080 source is not accepted merely because a remote branch
exists. All18 frozen release gates remain required; optional052 Vulkan remains
disabled and cannot replace CPU-path qualification.

Agent094's scope-correct delivery
`4f9bc76c42c96f571ba885975b23bd204f148ef3` is included in frozen86. Its remote was
subsequently advanced by other campaign merges to
`bf3c6ea2b0d8043a8ddb882d487473733de7228a`. That tree has365 paths outside094
ownership. It is preserved unchanged;100 retains the already reviewed supplying
commit rather than accepting the mixed tree. Its internally valid manifest is
not proof of source ownership. The independent audit is hash-referenced in the
machine evidence.

Agent100's contract records concrete071/078/088/099 incoming dispositions.
Existing owner handoffs name missing publication/job/database/SAF/UI adapters,
localization and accessibility work. `100-to-004.md` supplies the actual verified
release wiring and fixed bootstrap evidence. Real authorized whole-object scenes,
their calibrated references, physical handset matrix, production signing identity
and external beta remain unavailable. Source-owner fixes and missing evidence
must be delivered and retested before acceptance; red gates are not waived.

## Environment setup

The saved onboarding draft contains verified JDK17, SDK36, NDK28.2, Gradle8.13,
CMake/Ninja, the hash-checked095 Python graph with pytest9.0.3, and documented
optional software-emulator setup. The corrected host environment passes pip-check
and117 original baseline tests. The draft also permits the official portable
Blender download domain for a future environment build. The published environment
configuration is unchanged until the saved draft is published in Environment
settings. No fresh portable-Blender installation success is inferred from that
network-policy edit; actual local QA used the existing verified Blender4.3.2.
