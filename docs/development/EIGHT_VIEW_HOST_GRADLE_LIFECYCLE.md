# Eight-view host probe task-registration lifecycle

The additive init script formerly resolved `testDebugUnitTest` and
`hostIntegrationTest` in its `afterEvaluate` callback. Plugins or the app build
can register those tasks in later callbacks, so lookup could fail before the
host experiment reached compilation or execution.

The test source-directory addition remains in `afterEvaluate`; task lookup and
registration happen in `gradle.projectsEvaluated`. That callback now obtains
**unrealized task providers** only. Missing required task names still fail.

Commit `076259b4155e4130482ae4e34b8a0e21c4da609e` exposed a second boundary:
eagerly realizing `hostIntegrationTest` there executes its existing app-build
configuration closure, including `ordinary.taskDependencies.getDependencies`.
Gradle 8.13 refuses configuration-backed dependency traversal without the app's
project-state lock. The fix realizes the host task only from normal probe task
configuration or execution, and defers preparation dependency traversal to graph
construction. Providers are not mapped into file collections: that would carry
the ordinary Test producer and could schedule its unrelated tests/oracle.
The app's ordinary host task implementation and gates are unchanged.

AGP 8.11.1's `AndroidUnitTest.CreationAction.configure` sets test class directories
from scoped compiled artifacts and classpath via `computeClasspath`, including
runtime configuration-backed CLASSES_JAR/JAVA_RES artifact collections. Source
inspection supports the configuration-backed model below; it is not AGP execution.
Official source: [AGP 8.11.1 source archive](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.11.1/gradle-8.11.1-sources.jar),
`com/android/build/gradle/tasks/factory/AndroidUnitTest.java`.

`tests/test_eight_view_gradle_lifecycle.py` runs the complete current init script
against a small synthetic Gradle project with deliberately late task registration.
Its ordinary Test has a real Gradle Configuration-backed classpath; its host
constructor mirrors the production dependency traversal. It verifies both original
lookup failure and the exact project-state-lock failure, corrected configuration,
source registration, unchanged ordinary dependencies and missing-task refusal.
It also dry-runs both added task graphs, requiring exactly the synthetic compiler,
SDK check and selected probe tasks—no ordinary Test, host Test or oracle task.
A local synthetic AAR contains inert non-bytecode bytes solely to exercise the
unchanged classpath provider during graph construction. No real OpenCV classes,
SDK, network artifacts, compiler, test fork or pipeline are executed by this test.
Runtime capture/owner attestation and actual AGP integration remain unqualified.

Run with an official Gradle 8.13 installation:

```sh
RF_GRADLE_HOME=/path/to/gradle-8.13 RF_GRADLE_REQUIRE_RUNTIME=1 \
  python3 -m unittest discover -s tests -p test_eight_view_gradle_lifecycle.py -v
```

Without the runtime prerequisite, the runtime test explicitly skips; this is not
qualification. Actual Gradle 8.13 synthetic lifecycle execution does not establish
AGP compatibility, OpenCV/JNI loading, Android compilation, emulator execution,
reconstruction quality or physical-device readiness. Those remain separate gates.

## Provider-count refusal diagnostics

The subsequent `c11519cffb670d0930510e40e7e1c0e1d7f91852` HOST attempt passed
configuration and compilation, but preparation refused the existing OpenCV
provider count. Its retained log did not report the count or paths, so zero,
duplicate and non-JAR providers cannot be distinguished from that artifact.
AGP's runtime `AarToClassTransform` and `AarTransform` resource path can expose
different containers; source inspection alone does not establish this run's inputs.

On that same refusal only, the probe now emits one structured
`EIGHT_VIEW_PROVIDER_DIAGNOSTIC` record into the already-retained Gradle command
log. It includes the actual matching-JAR count, original entry indices, kinds and path
identities, Core presence, matching class-map/native-owner hashes, and expected
AAR/classes hashes. Directory Core hashes are bounded and omitted for oversized
or aliased/symlinked files. Paths are capped at 1024 characters with explicit
truncation flags and full-path hashes. At most 256 entries are reported; the
record is trimmed below 256 KiB with omitted-entry counts. Known matching JARs
have priority regardless of original index, then directories with observed Core,
then generic entries; byte trimming removes generic entries first. Directory Core
discovery uses at most 4096 metadata checks, with unexamined counts explicit. No
additional JAR scan is performed. Matching-JAR and observed-directory omissions
are counted separately; the execution classpath order is never changed. Diagnostic I/O failure
is identified without replacing the original refusal. Existing log sanitization
and artifact limits are unchanged; no class bytes or additional artifacts are
uploaded. The successful path does not log this record.

This is diagnostic-only: the exact-one-provider assertion, complete class-byte
comparison and later owner binding remain unchanged. No provider is selected,
dropped, deduplicated or appended as a workaround. Real Gradle synthetic tests
cover single, zero, duplicate, directory, truncated, late providers, byte-cap
trimming and differing-byte cases;
these are not OpenCV/AGP runtime qualification. A further actual-run inventory is
needed before selecting any production remediation.


## Proven equivalent AGP representations: explicit resource projection

Attempt `0508afab6` retained a complete 217-entry inventory: entries 118 and 213
were the AGP runtime JAR and extracted AAR `classes.jar`. Both had
212 identical class entries matching the pinned AAR; all four OpenCV native-owner
hashes matched and neither contained scanner `NativeCore`. This proves the
class equivalence of those observed inputs, not their unrecorded resources or
successful pipeline execution. The attempt stopped before any pipeline call.

The supplementary probe now admits only these explicit modes:

- `single-class-equivalent-provider`: one complete class map equals the pinned
  AAR. It remains unchanged. Role `single-source-resources-identical` means its
  ordered resources match the source; `single-class-only-no-resource-completeness`
  means no non-class file payload was observed (directory markers are retained).
  The latter does **not** claim resource completeness or add AAR resources.
- `agp-two-role-resource-projection`: exactly one provider's raw SHA/size equals
  the extracted AAR classes archive; the other has the complete identical class
  map and no non-class file payload. The runtime role must precede the resource
  role. Keep the runtime JAR unchanged and replace only the resource-role slot
  with a deterministic resources-only archive. All non-class bytes, directory
  markers, entry order and resource lookup multiplicity stay at that position.
  Unrelated classpath entries/order stay unchanged. Resource URLs at that slot
  intentionally change; the ledger explicitly records the mapping.

No role is inferred from filenames, observed indices or arbitrary first-match
selection. Third/partial providers, canonical-class directory overlaps, different
class bytes, ambiguous/reversed roles and unsupported resource/loader metadata
fail closed. Discovery reads bounded metadata for any canonical AAR class name,
not just Core; unrelated JAR payloads are not scanned for normalization. Candidate
archives reject unsafe/duplicate names, symlinks/special entries, signatures,
multi-release/sealing/Class-Path metadata, ZIP64 and multi-disk layouts. Limits
are 512 MiB raw/expanded, 2000 entries, 16 MiB per entry and derived archive. ZIP names
are printable ASCII with safe components; digest ordering is cross-language
unambiguous. Derived files are deterministic and immutable once written.

The original extracted AAR classes archive remains unchanged as `opencvClasses`
for ABI/source checks. Capture schema 2 introduced replacement of the obsolete singular
`replacedOpenCvProvider` with versioned `opencvNormalization`, binding source AAR,
original provider indices/paths/raw hashes and ordered entry inventories, explicit
roles, and projected identity. Groovy production captures and independent Python
validation re-read original and projected inputs with before/after identity checks
at preparation, before fork and postcheck. Prepared-record immutability and the
existing unique-native-owner and Kotlin single canonical class-resource URL gates
are preserved; the pinned adapter is unchanged.

Tests execute actual Gradle 8.13 with compiled synthetic Java classes and use
isolated Java URLClassLoaders to compare interleaved resource contents/order/
multiplicity and unique class ownership. A real Gradle-produced schema 2 capture
is admitted by Python's independent validator. Negative cases cover partial and
third providers, changed resources/classes, reversed roles, unsafe archives,
unsupported metadata and projection/ledger/original-input tampering. These remain
synthetic host tests, not AGP/OpenCV execution or Android qualification. The next
actual attempt must still validate its full resource inventory and may refuse.

## Bound absence of declared optional directories (capture schema 3)

Attempt `63fe19dea0264f68f91d61e95beca202a921b78a` reached later classpath
inventory, then refused absent KSP unit-test resources. The earlier inventory
also recorded the absent Javac unit-test classes directory; the actual Gradle
log reports `compileDebugUnitTestJavaWithJavac NO-SOURCE`. No prepared ledger was
retained, so this control-flow progress is not full normalization qualification.
No pipeline invocation occurred.

Schema 3 preserves every classpath position and the entire ordered classpath
string, with strict `jar`, `directory` and `absent-declared-directory` entries.
An absent entry is not a fabricated empty directory and is never dropped.
Only these exact paths beneath the selected repository's canonical `app/build`
may use the absence tag:

- `generated/ksp/debugUnitTest/resources`: the pinned KSP2 task's actual
  `kspConfig.resourceOutputDir` (`Property<File>`, `@OutputDirectory`).
- `intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes`:
  the real `JavaCompile.destinationDirectory`, requiring actual `NO-SOURCE`.

Task type, property, declared-output membership and execution-graph membership
must match. The producer must be enabled and have completed without failure.
KSP permits success, up-to-date, cache restoration or no-source outcomes, never
arbitrary `onlyIf` skipping. Javac must specifically report `state.noSource`.
No missing task/property/type or pathname-only fallback is permitted. Volatile
execution status is checked at capture but not embedded in immutable equality.
The pinned source declarations were inspected in the official
[KSP 2.1.21-2.0.1 task source](https://github.com/google/ksp/blob/2.1.21-2.0.1/gradle-plugin/src/main/kotlin/com/google/devtools/ksp/gradle/KspAATask.kt).

Absence is established only by precise NOFOLLOW metadata observations and a
readable parent-directory listing. Existing ancestors from `/` through the first
missing component's parent are bound by canonical paths and unsigned 64-bit
decimal device/inode identities. Permission failures, unknown errors, ENOTDIR,
symlinks, ancestor substitutions and file/directory/symlink appearance refuse.
The walk is bounded to 64 absolute components and 100000 parent entries. Both JVM
capture and independent Python validation regenerate the proof; the selected
repository binds `appBuildDirectory`, which joins immutable runtime fields.

These bindings are checked at preparation, runtime preparation, before fork and
after the selected invocation. Deliberate normal-source restoration compilation
occurs afterward, outside that baseline window. Owner extraction still requires
all five original owners exactly once; absent entries cannot satisfy class or
native-owner bindings. Compilation dependencies, source-AAR normalization and
Kotlin adapters remain unchanged.

Tests distinguish the synthetic KSP declared-output model from the genuine
Gradle `JavaCompile` task and its actual NO-SOURCE result. Actual Java classloader
and javap checks exercise the unchanged classpath with absent slots. Cross-language
schema 3 admission, stable recapture, producer-refusal cases and appearance/
permission/ancestry tampering are tested. None of this certifies actual AGP/KSP
execution, native loading, Android behavior or photo reconstruction.

## Focused system-header admission diagnostic after actual 596c

Actual `596cbee68cbf954536d9b60bdb83197082f72d60` completed the 33-task
Gradle preparation and retained schema-3 `prepared.json`. Its 217-entry
classpath records the two bound absent outputs at indices 2/3. The actual
OpenCV normalization ledger has 212 matching classes in each original
provider, the unchanged class-only runtime at index 118, and a 2,718-byte
resource projection at index 213 preserving 17 directory markers and the
65-byte Kotlin module metadata. This establishes actual preparation, **not**
JNI loading, photo execution, sparse reconstruction, or device qualification.

The next failure is the first system-header prebuild snapshot refusing an
escaping symlink. The old exception retained neither the encountered link
nor its resolved target; the artifact cannot identify it retrospectively.
No particular package or target is presumed and the admitted roots are not
expanded. The new `EIGHT_VIEW_HEADER_ESCAPE` record supplies only lexical
candidate, resolved target, readlink metadata, and admitted roots. Every text
field has a bounded preview, complete-text hash/byte length and explicit
truncation; root omissions are counted. JSON is at most 16 KiB. No escaped
target payload is opened by the diagnostic and the original refusal remains.
Existing terminal secret-marker withholding still applies.

The focused one-shot workflow runs the same `prebuild_snapshot` and shared,
unchanged `system_header_roots(java)` domain on a fresh Ubuntu 24.04 public
runner with the same preinstalled JDK-17 selection and compiler environment.
It needs no Gradle, Android SDK/NDK setup, OpenCV download, or photos. Actual
inventory is bounded to 90 seconds; a five-minute job records at most 32 KiB
of JSON for one day. Source/event guards bind the exact single parent
`596cbee68cbf954536d9b60bdb83197082f72d60`, exact seven changed paths and message
`test: diagnose host header admission once`; reruns, arbitrary roots, manual
activation, force-pushes and non-main/private contexts are inadmissible.
A new runner's evidence describes that runner, not the lost 596c filesystem.
Any admission failure is retained as failure, never promoted to capability or
pipeline success. This workflow does not re-arm the photo diagnostic.

## Exact Clang-18 resource-header provider after focused 5f5a

The focused run at `5f5a180f52d0ee57c254118592688e615c70df35` retained the
actual encountered alias `/usr/include/clang/18.1.3/include`, raw target
`../../../lib/llvm-18/lib/clang/18/include`, and canonical target
`/usr/lib/llvm-18/lib/clang/18/include`. That is evidence for the 5f5a runner
image `20261012.334.1`, not retrospective identification of the 596c link.

Admission is restricted to that exact installed-provider role. The fixed
`/usr/bin/clang-18` entry must resolve to `/usr/lib/llvm-18/bin/clang`.
Bounded, authoritative `--version` and `-print-resource-dir` probes must
identify major 18 and the exact resource directory above (before `/include`).
The latter flag's meaning is documented in the official
[Clang command-line reference](https://clang.llvm.org/docs/ClangCommandLineReference.html#cmdoption-clang-print-resource-dir).
Each probe uses the existing native compilation environment boundary, a
10-second deadline and a live 4-KiB output cap. This does **not** select Clang
as the build compiler: the GNU compiler contract is unchanged.

The source/run-bound provider proof records the executable hash, NOFOLLOW
ancestor/directory/link identities, exact alias target and authoritative
probe outputs. These identities are checked before/after discovery, after
the header snapshot, after the native build and by capability verification
at runtime boundaries. No symlink ancestor or arbitrary LLVM/version root
is admitted. The original dynamic system-root discovery is retained; only
the validated exact include root is added. Unknown escapes still refuse.
The consumed-header closure accepts that root only after independently
validating the proof. Existing consumed-byte hashes and pre-consumption
identity checks remain mandatory.

Provider admission is explicitly `ADMITTED_ROOT_NOT_CONTENT_SNAPSHOT`.
The proof is written into the existing retained pending capability receipt
before traversal, so another refusal does not erase the useful provider
finding. Full snapshot completion is separate and never asserted after a
failure. The focused runner likewise retains admission proof while reporting
an original failure. Artifact size/secret guards, 90-second snapshot,
270-second controller, five-minute job, and one-day retention are unchanged.

The next focused verification is limited to exact parent
`5f5a180f52d0ee57c254118592688e615c70df35`, exact ten changed files, and message
`test: verify clang header admission once`. It does not activate the photo
workflow, install tools, change system permissions, skip headers, or establish
native/pipeline qualification. Local provider fixtures include real owned
subprocess execution of an explicitly synthetic driver script; they do not
claim validation of the runner's installed Clang package. The focused CI run
must establish that separately.

## Vanished task inventory after focused 48a4

Run `48a45fa54eca88ec3383702ca0c33bae95327478` failed before header
admission because Linux returned `ESRCH` while reading an exiting task's
`/proc/<pid>/task/<pid>/children`. Snapshot execution was not attempted;
owned cleanup passed. `child_pids` now treats only `ProcessLookupError` as
that vanished task's empty child inventory. The existing `ENOENT` bounded
PPID fallback remains unchanged for procfs views lacking `task/children`.
Permission, I/O and other errors still propagate; `proc_info` already handled
both vanished-task errors and needs no change. PID/starttime checks, pidfds,
process groups, subreaper restoration and cleanup gates are unchanged.
Deterministic tests exercise the exit-between-check-and-read race, subsequent
adopted-child discovery and PID reuse refusal. Real short-lived child and
orphaned descendant tests verify owned cleanup and subreaper restoration.
The combined focused verification is bound to exact parent
`48a45fa54eca88ec3383702ca0c33bae95327478`, exact seven changed paths and
message `test: verify header admission after process race fix`. Only the
short header workflow is re-armed; the photo workflow and all limits remain
unchanged. Publication remains subject to independent review.

## Bounded all-escape metadata inventory after bafb

The focused `bafb4a0b54b50ec6536ba1e432a0fe87cbfe46ca` run established that
Clang-18 provider admission and its postcheck pass. The unchanged content
snapshot then refused `/usr/include/clang/16/include`, resolving to
`/usr/lib/llvm-16/lib/clang/16/include`. Rather than repeatedly admitting the
next observed version, the next focused run inventories the complete bounded
metadata domain first. It adds **no provider or admitted root**.

This diagnostic runs **instead of** the 90-second content snapshot, never in
addition to it. Production `prebuild_snapshot` is unchanged. The entrypoint
marks content snapshot `NOT_RUN_METADATA_ONLY`, keeps `qualified=false` and
`pipelineAttempts=0`, retains the verified Clang-18 proof and rechecks it.
An inventory with escapes or incomplete coverage remains a failed admission;
`INVENTORY_COMPLETE` without escapes is metadata observation, not content or
native qualification.

Only existing canonical roots are enumerated, using retained root directory
FDs, component-by-component `O_NOFOLLOW` opens and directory identity checks.
Symlinks are inspected with metadata operations but **never traversed for
directory enumeration**. Canonical in-root directories are already reached
through their physical tree. Escaped targets are never opened for payload or
enumerated. Directory identity and modification metadata are checked around
enumeration and again before completion; observed mutation makes coverage
incomplete. This is bounded observation, not an atomic filesystem snapshot.

Limits are 90 seconds, 60,000 entries, 128 escape rows, 32 error rows and
20 KiB inventory JSON within the unchanged 32-KiB total receipt. Root/row
omissions, text truncation, errors and limit hits are explicit and force
`complete=false`; permission errors, broken/looped links and special files
are never treated as absence. Cancellation/deadline stops work immediately.
The inventory reports lexical link, raw target and canonical target for each
retained escape. It neither repairs links nor follows arbitrary target roots.

One reviewed activation is bound to exact parent
`25a0176d7cd93ba451932cf65b016331ca13038b`, exact seven changed paths and message
`test: inventory host header escapes once`. Five-minute job, 270-second
controller budget, one-day retention and source/postcheck/secret guards stay
unchanged. The full photo workflow is not re-armed. Complete hosted metadata,
not guesses or synthetic fixtures, must inform any later provider proposal.

## Closed complete Clang provider set after hosted 556d inventory

The `556d673481d1a98ab703c188c7b9fbcfc5b9675d` inventory completed with
12,117 entries, 25 symlinks and exactly four escaping aliases; errors,
omissions, truncation and limit hits were all zero. The complete observed
additional domain was `/usr/include/clang/{16,16.0.6}/include` resolving to
`/usr/lib/llvm-16/lib/clang/16/include`, and corresponding `{17,17.0.6}`
aliases resolving to `/usr/lib/llvm-17/lib/clang/17/include`. Existing
Clang-18 admission and provider/source postchecks passed. This is hosted
metadata evidence, not yet complete content or native qualification.

Provider schema 2 admits the closed ordered set 16, 17, 18. Each fixed
`/usr/bin/clang-N` entry must resolve to `/usr/lib/llvm-N/bin/clang`; its
bounded real version probe must report that major, and its resource-directory
probe must report exactly `/usr/lib/llvm-N/lib/clang/N`. Both observed aliases
for 16/17 and the already bound 18.1.3 alias must have the exact recorded raw
relative target and canonical include root. Missing tools or different
outputs refuse; nothing is installed or guessed. Invalid version/resource
outputs retain bounded hex previews, byte counts and hashes in the refusal.

The immutable proof deduplicates shared NOFOLLOW filesystem identities and
binds all three executable hashes, five aliases, exact roots, source/run
identity and six authoritative probe records. All drivers and nodes are
checked before and after the entire probe sequence, after the snapshot and
build, and during existing runtime capability validation. Missing, reordered,
duplicate or fourth providers are inadmissible. Capability inputs require
three driver identities plus the proof file; the consumed-header closure
adds only the three validated roots. GNU remains the actual build compiler,
and consumed bytes/prebuild provenance, native ABI and unique runtime owner
gates are unchanged.

The focused entrypoint now returns to the actual content snapshot, not the
metadata inventory. Six probes retain their live 10-second/4-KiB bounds;
the snapshot receives at most 90 seconds **and no more than remaining normal
budget**. Slow setup/probes fail closed rather than consuming cleanup reserve.
The 270-second controller, five-minute job, 32-KiB receipt and one-day
retention are unchanged. Combined larger-proof/maximum-escape receipt guards
are regression-tested. Local real-process tests use explicitly synthetic
versioned drivers; installed-provider qualification still requires hosted CI.

The one-shot verification is bound to exact parent
`1cf744bffd4690aaf5f11d3ab942d87758d5e3da`, exact thirty-one source paths and message
`fix: bind header providers and upright calibration`. The full photo workflow
remains unarmed until a separately reviewed content-snapshot result supports
continuing. Snapshot success alone is not pipeline or device qualification.

The final combined commit also carries the independently reviewed seven-path
upright-calibration handoff correction unchanged, avoiding a redundant full
CI trigger. Those paths are explicitly included in the one-shot source guard;
they do not widen provider/root admission or alter the diagnostic budget.
The parent silent-native-log sealing correction is preserved unchanged.

With seventeen combined source paths, a long temporary-path synthetic proof
plus maximum failure text exceeded 32 KiB and was correctly withheld. The
receipt regression now retains that oversized negative and separately checks
a clearly labeled fixed-production-path serialization layout, including
maximum-width inode/device/timestamp values. Rewritten serialization fixtures
are not filesystem/provider admission evidence. The independent real synthetic
acquire/validate controls remain; actual hosted receipt fit is still observed
only by the next run. No production output cap or metadata was relaxed.

The same-parent final union additionally carries the independently approved
fourteen-path native pytest/subtest producer-accounting correction unchanged.
The exact header source guard enumerates all 31 combined paths, with the
original message retained. The accounting proof is reviewed separately; no
global JUnit count waiver or provider admission expansion is introduced.
The 32-KiB receipt cap remains mandatory regardless of the longer source list;
oversized evidence is explicitly withheld, never silently truncated to PASS.

## OpenCV build/install RPATH omission after actual aa340

Actual `aa340190a3babb8877be9d37b4f7ab953a7bad9a` completed its OpenCV
native build but the unchanged ELF closure gate refused a RUNPATH consisting
of exactly 66 colons. The system dependency closure contained libz, libm,
libstdc++, libgcc_s and libc; `ldd -r` showed no unresolved symbols. The
selected photo invocation had not started (`pipelineAttempts=0`); restoration
and cleanup passed without secondary errors.

Pinned OpenCV commit `49486f61fb25722cbcf586b7f4320921d46fb38e` sets
`CMAKE_INSTALL_RPATH` to `${CMAKE_INSTALL_PREFIX}/${OPENCV_LIB_INSTALL_PATH}`
in `cmake/OpenCVInstallLayout.cmake`. The JNI target is SHARED for
`BUILD_FAT_JAVA_LIB` and has an install rule. The actual prefix plus `/lib`
is 66 characters long. CMake reserves build-tree RPATH space for later
installation editing; a real small CMake 3.31.6 ELF fixture with that exact
prefix reproduces all 66 colon padding characters.

The fix adds only `-DCMAKE_SKIP_RPATH=ON` to the **OpenCV** configure command.
Official [CMake 3.31 documentation](https://cmake.org/cmake/help/v3.31/variable/CMAKE_SKIP_RPATH.html)
and the installed 3.31.6 help describe omission of runtime path information;
[INSTALL_RPATH](https://cmake.org/cmake/help/v3.31/prop_tgt/INSTALL_RPATH.html)
describes build/install editing. The exact same real fixture, rebuilt with
this flag, has **neither DT_RPATH nor DT_RUNPATH in the build output before
installation**, retains identical DT_NEEDED entries, resolves `ldd -r`, and
loads/executes successfully. Its installed copy likewise has no RPATH tag.
No `patchelf`, post-build removal, empty-path admission or ELF parser waiver
is used. This small fixture proves construction behavior, not a fresh OpenCV
or reconstruction run.

Capability verification also requires exactly one authoritative configure
argument `-DCMAKE_SKIP_RPATH=ON`; omission, OFF, duplicate or conflicting forms
are refused. The strict ELF parser is unchanged. Scanner configuration and
the scanner JNI shim's required explicit engine RUNPATH remain unchanged.
The existing source/ABI/dependency/provider/SDK-license gates and all budgets
are unchanged. Local reproduction uses the official Kitware CMake 3.31.6
Linux archive with publisher SHA-256
`5a1133ff103c71eb5120e2cc3de922733e7d8a26a98ae716397e8676adb367bf`.
Set `RF_CMAKE_EXECUTABLE` to that tool and `RF_REQUIRE_RPATH_TOOLS=1` when
requiring the real fixture; no missing-tool skip may count as qualification.

The reviewed source-fix plus one-shot rearm is bound to exact aa340 parent,
seven enumerated source paths and message
`fix: omit OpenCV build runtime search paths`. Production invocation remains
600 seconds within the existing 4,200-second overall deadline, which already
reserves 120 seconds for cleanup; the reservation is not extra runtime.
Actual hosted OpenCV output must still pass the unmodified strict ELF gate.

## Role-bound consumed inputs after actual efb OpenCV success

Actual `efb0f748a45d7e7523beec336e185e86ea303ff7` passed the unchanged
OpenCV no-RPATH gate, Java smoke, scanner build/shim and native ABI/ELF/loader
checks. It then refused consumed-input provenance before reconstruction
(`pipelineAttempts=0`); restoration and cleanup passed. The retained ledger
contains 1,087 consumed inputs and 19,948 prebuild inputs: 77 missing entries,
zero shared digest mismatches. These comprise 21 OpenCV build products,
37 scanner objects and 19 system linker/CRT/script/library inputs. `ninja -t
deps` includes actual linker dependency records, not only source headers.
The old immutable-only binder therefore cannot truthfully classify them.

The replacement keeps three distinct, recomputed roles:

* Immutable source/configured headers remain equal to their prebuild hashes.
* A small, source-bound C++ shared-link probe selects the existing GNU driver's
  CRT, scripts and transitive libraries using exactly `-ldl -lpthread -lrt -lz
  -lm`. It runs before native builds, records the selected linker identity and
  dependency file, and snapshots only those exact canonical external members.
  Discovery itself is restricted to canonical `/usr/lib/gcc` and
  `/usr/lib/x86_64-linux-gnu` locations; this is not directory-wide admission.
  The probe's own fixed object is excluded explicitly. Actual later external
  inputs must be unchanged members; `/usr/lib` is **not** generally admitted.
* Generated objects/archives require a non-phony declared producing edge
  reachable from the selected target, initial absence, a new current-run
  successful owned build log record, recursively bound graph and actual
  compiler/linker dependencies, and current regular-file identities/hashes.
  This role is **postbuild current-run evidence, not preconsumption bytes**.

The exact hosted Ninja binary is the official 1.13.2 Linux release (SHA-256
`607e668f90dd6cd82e1a42ae572647ad1b1fd43063964295b9547836d8c15d99`).
The controller still probes its version authoritatively and supports only
that exercised protocol. It uses strict targets/graph/query, dependency and
log-v7 formats, refusing unknown formats or incomplete records. Complete
configured graph discovery is metadata only; admission is restricted to
selected-target reachability. Canonical aliases are retained only when the
same explicit graph edge produces them and their input/rule and log records
agree. Ninja's log timestamp is a command-start/restat marker, not invariably
a file timestamp; freshness uses initial absence, a new owned phase record
and independently bound final stat/hash, never a fabricated timestamp match.

Pinned OpenCV's Java generator declares only the `gen_opencv_java_source`
stamp, not its JNI headers. Its OpenCL generator intentionally declares only
`.cpp`, not `.hpp`. Those side effects cannot acquire a fictional graph edge.
An exact owned pre-generation batch therefore runs the Java target and the
three core/imgproc/features2d OpenCL `.cpp` targets, after verifying declared
producer reachability and configuration. The source and configured graph
must remain unchanged. The resulting bytes are snapshotted **before native
compilation** and assigned an explicit generation-phase preconsumption role.
A retained generation log distinguishes that phase from compilation; changed
bytes or regeneration (even identical bytes) are refused. No upstream source
editing or unconditional four-header exception remains.

Generation and native compilation share the existing 1,620-second OpenCV
allowance: generation has at most 120 seconds, and compilation receives only
the remaining allowance. Overall 4,200 seconds including its 120-second
cleanup reserve, the 600-second selected invocation, ELF/shim rules, SDK
license snapshots, typed BA/eight-view selector and no-oracle gates do not
change. Discovery is bounded to 24,000 nodes, 100,000 edges, 128 manifests and
60,000 metadata entries; command argument/environment bytes must fit the
actual system limit with reserve. Existing 8 MiB command logs, 16 MiB proof
file and 32 MiB complete artifact bounds remain fail-closed. Capability
receipts reference the single retained proof by exact path/hash rather than
embedding it twice; oversize evidence is refused, not truncated into success.

Local controls use official Ninja 1.13.2 and CMake 3.31.6 with the available
local compiler/linker, not a claim that hosted GNU 13/OpenCV has rerun. Real
fixtures cover undeclared generated-header side effects, objects, archive,
shared linking, linker-script/transitive-archive discovery, failed generation
and identical-byte regeneration. Adversarial controls cover source/header/
output/system drift, unlisted/unreachable/phony producers, cycles, partial
records, ambiguous aliases, stale logs, unknown targets, command limits and
cancellation. Run with `RF_NINJA_FIXTURE_ROOT` pointing to a directory
containing `1.13.2/ninja`, `RF_CMAKE_EXECUTABLE` selecting CMake and
`RF_REQUIRE_NATIVE_PROVENANCE_TOOLS=1` to prohibit skipped real fixtures.
Actual full OpenCV and reconstruction qualification still require hosted
execution under every unchanged admission gate.

The next one-shot is bound to exact parent
`4e80a5afc26ef5aad04a5c2d1b76151a4d74b10f`, the nine enumerated changed paths,
and message `fix: bind native compiler input provenance`. Event trigger paths
remain the original workflow/controller pair; standalone test edits do not
activate this diagnostic. The source-only fixture proof is about 290 KiB,
not a guarantee that the larger actual OpenCV evidence will fit; the same
per-file and aggregate caps must admit the complete hosted evidence.
