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
