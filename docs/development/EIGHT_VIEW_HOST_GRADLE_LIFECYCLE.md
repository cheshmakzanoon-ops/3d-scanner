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
