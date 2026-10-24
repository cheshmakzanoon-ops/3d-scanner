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
discovery uses at most4096 metadata checks, with unexamined counts explicit. No
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
