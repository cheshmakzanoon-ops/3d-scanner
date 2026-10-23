# Eight-view sparse runtime successor — source candidate, NOT_RUN

This separate successor does not modify the published immutable source checkpoint
`31b4fcda16d368c72707a32d99abeb479a84e16f849ff877c0a97ec9a7841261`.
The actual SparsePipeline, production sources, declared eight-image policy, import,
COMPACT settings, 28-pair evidence and SPARSE stopping barrier remain unchanged.
No native build, Android SDK license action, workflow dispatch, pipeline compilation
or reconstruction measurement has occurred. Controller/workflow remain NOT_ARMED
until independent review and a separate explicit source qualification checkpoint.

## Source-built capability, not arbitrary replacement pins

`CandidateNativeLoaderTokenHostTest` now calls `QualifiedCapability.load`, which
requires the exact reviewed build-policy hash
`4d727401861b7d460f3e6c4a30a99b34d7db59a0f3a930920d169d872a3b1820`, source SHA,
manifest SHA, bounded canonical files, source-built native roles/expected paths,
checks, source/tool/header/ABI proof files and producer transcript hashes. Required
properties are `rf.loader.capabilityManifest`, `capabilitySha256`, `sourceSha`,
`policySha256`, `runOwner`, `workDir`, `opencv`, `shim`, `engine`, `aliasDir`, and `classSha.<owner>`.
The reviewed controller MUST create the capability internally after independently
checking its own in-memory process records, actual commands, logs, current JNI
receiver/descriptor contract, official AAR/native ABI, consumed source/system/JDK
headers, actual smoke output and resolved ELF providers. Kotlin checks byte
integrity and structural consistency again; it does not recreate or substitute
for the controller's authoritative producer verification. A schema-valid
self-declared manifest is not a supported admission path. No old historical
native binary hash is reused or silently trusted.

Original current OpenCV AAR owners and current NativeCore are acquired by the
same Robolectric sandbox; distinct canonical class provider locations must be unique and all enumerated bytes match the
prepared runtime inventory. NativeCore class bytes also match the actual class
used to compile the source-built JNI descriptor contract. The unchanged real
OpenCV loader/Mat and native token/progress/cancel/finish smoke remain in place.
Only the existing opaque Bitmap→Mat conversion is a platform substitute; no fake
SIFT/init/Room/resource-health/camera success is introduced.

## Actual native-provider evidence

After real Bitmap pixel activity and `database.openHelper.writableDatabase.query`
with `SELECT sqlite_version()`, and again after Room reopen, the adapter reads only
its own JVM `/proc/self/maps` (bounded4MiB/32768lines). It hashes only admitted
scanner/OpenCV/system ELF providers and the observed Android runtime library.
Target providers must match canonical paths, actual mapping inodes and bytes.
Missing, duplicate, deleted, foreign or differing providers are UNQUALIFIED and
fail the selected test while retaining bounded diagnostic evidence. No other
process, environment, permissions, namespace, mount or persistent security setting
is read or changed.

API35's `native/linux/x86_64/libandroid_runtime.so` is matched to the unique entry
in pinned offline android-all-15-robolectric-12650502.jar
`6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b`.
The entry is exactly15,526,048bytes, admitted under a32MiB bound. The compat1.0.16
JAR remains a separately pinned dependency (`2f4e879b00eed634d0e43353ecff80db4d5ce24b3b213d1e6053cb21b0ced10f`),
NOT the API35 provider archive. Both dependency archives are rechecked unchanged.
The actual extracted file must be below the explicit dedicated JVM temp directory
and have the archive entry's exact byte count/hash. Graphics and SQLite may share
this library; no separate SQLite provider is invented. The receipt proves the
observed mapping identity following those activities, not symbol-level attribution,
Android physical-device behavior, performance or decoder equivalence.

The controller must independently verify both `runtime-providers-after-bitmap-room.json`
and `runtime-providers-after-room-reopen.json`: status
`OBSERVED_PROVIDER_IDENTITIES_MATCH`, capability/policy/source binding, actual
runtime entry identity, native role identities and preserved provider identity
across reopen. A JAR hash alone is not mapped-provider evidence. These receipts
are currently NOT_RUN. Pipeline failures before that point remain failures,
not missing-provider successes.

## Exact source integration contract

Compile only these runtime probe sources with the actual current app/Room test
classpath: CandidateNativeLoaderTokenHostTest.kt, EightViewSparsePrefixHostTest.kt,
EvidenceFailureHandling.kt, CapabilityIdentity.kt, QualifiedCapability.kt, RuntimeProviderIdentity.kt,
OpaqueBitmapBridge.kt, SparsePrefixRunner.kt, SandboxNativeLoaderRunner.kt.
Do not include the standalone `*Check.kt` main programs in the selected JUnit probe.
Selector and existing rf.decoder.fixtureDir / rf.eight.evidenceDir properties are
unchanged. Always use strict observation staging/validation from inputs.py;
only the fixed manifest and all eight images enter reconstruction. Truth is
reserved for separate evaluation. This is audited input/dataflow isolation, not
OS confinement. Production sources and experiment acceptance policy are frozen.

## Source-only tests and limitations

From this adapter directory:

```
RF_EIGHT_FIXTURE=/absolute/repo/docs/development/experiments/new-cuboid-eight-view-160x120 \
  PYTHONDONTWRITEBYTECODE=1 python -m unittest -v test_inputs
RF_EIGHT_FIXTURE=/absolute/repo/docs/development/experiments/new-cuboid-eight-view-160x120 \
  PYTHONDONTWRITEBYTECODE=1 python -O -m unittest -v test_inputs
```

14 input controls pass normally and optimized. The six pure evidence-failure
controls remain inherited. Thirteen new pure Kotlin provider/parser controls
compile and pass on the existing JDK17/Kotlin2.1.21 against explicitly synthetic
non-library bytes: valid byte identity, missing/duplicate/deleted mappings, inode,
path/hash/length mismatch, malformed map, ZIP entry/hash/missing entry.
They do not load libraries or qualify actual Robolectric/Room/native execution.
The full pipeline and JSON-dependent capability gate remain UNCOMPILED/NOT_RUN.

The original checkpoint's reviewed cleanup fixes remain: evidence-write failures
cannot bypass cleanup or replace the primary failure, and no resources are released
beneath a live worker. Actual disk-full/worker/native behavior is still not tested
by these isolated pure helper controls.

## Review2 corrections (prior failed checkpoint preserved)

Independent review of manifest2147067454bf744585635ecf9ad0876069e470f6d06aaa140ed38766394a51a2 blocked admission: generic nonempty hashing rejected successful empty compiler logs; producer run identity/prebuild provenance were not yet bound to updated trusted launcher fields; raw getResources count could reject one identical URL enumerated twice by Robolectric. That checkpoint is retained separately as failed review evidence, not qualified.

This revision permits zero bytes only for owned command-log records (8MiB bound), never native/input files. It binds producer runOwner/workDir/sourceSha to explicit launcher properties, each command owner/starttime/PID/environment and exact expected log path/label set, and prebuild/consumed provenance to the controller's narrow four generated-header exception. The authoritative controller still compares records to its own actual process history; Kotlin does not invent that evidence. Distinct canonical class provider locations, not enumeration count, must be unique and every returned resource must contain the original pinned class bytes. Same-URL duplication is accepted; genuinely different locations are rejected even if bytes match.

Sixteen additional pure Kotlin regression controls pass for empty successful logs versus empty non-log rejection, correct/stale/wrong producer owner/work/source, command owner/starttime/PID/environment, duplicate-same and genuinely different resource providers, wrong bytes/entry/missing resources. Synthetic class bytes are not actual app classes. Full loader/JSON gate/pipeline remains UNCOMPILED/NOT_RUN.

## API35 provider correction (coordinated review3)

The prior90e56198aa3223843477979ac70d45e7f8cfe135c6e7c283afe078522ffabdff checkpoint incorrectly named the compat archive as the source of libandroid_runtime.so. That failed provider assumption is preserved in the separate review2 checkpoint. Assessor's static inspection of the checksum-pinned loader and actual ZIP64 directories established API35 selection from the pinned android-all15 framework. This successor adds mandatory inputs.androidAll, exact prepared offline-framework path via robolectric.dependency.dir, framework SHA and unique offline inventory. runtimeProvider now binds that framework archive, exact native entry and15,526,048-byte size, with32MiB streaming/mapped-file refusal bounds. Compat remains mandatory separately; there is no fallback basename/archive or relaxed hash. Policy binding updated coherently to4d727401861b7d460f3e6c4a30a99b34d7db59a0f3a930920d169d872a3b1820.

Ten pure Kotlin regressions cover correct metadata, wrong compat archive/old basename, missing/wrong/oversize bytes and bounded synthetic ZIP streaming. All earlier producer/log/classloader/sandbox fixes remain. These tests do not load the actual framework or qualify pipeline/native execution.
