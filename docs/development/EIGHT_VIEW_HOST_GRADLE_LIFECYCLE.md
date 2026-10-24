# Eight-view host probe task-registration lifecycle

The additive init script formerly resolved `testDebugUnitTest` and
`hostIntegrationTest` in its `afterEvaluate` callback. Plugins or the app build
can register those tasks in later callbacks, so lookup could fail before the
host experiment reached compilation or execution.

The test source-directory addition remains in `afterEvaluate`; task lookup and
registration now happen in `gradle.projectsEvaluated`. Missing required tasks
still fail. Existing ordinary task dependencies and gates are not replaced.

`tests/test_eight_view_gradle_lifecycle.py` runs the complete current init script
against a small synthetic Gradle project with deliberately late task registration.
It verifies the original lookup failure, corrected task configuration and source
registration, unchanged ordinary dependencies, and missing-task refusal. It
realizes both added tasks but does not execute their compiler, SDK or pipeline
work. Runtime classpath resolution is deliberately outside this lifecycle test.

Run with an official Gradle 8.13 installation:

```sh
RF_GRADLE_HOME=/path/to/gradle-8.13 RF_GRADLE_REQUIRE_RUNTIME=1 \
  python3 -m unittest discover -s tests -p test_eight_view_gradle_lifecycle.py -v
```

Without the runtime prerequisite, the runtime test explicitly skips; this is not
qualification. Actual Gradle 8.13 synthetic lifecycle execution does not establish
AGP compatibility, OpenCV/JNI loading, Android compilation, emulator execution,
reconstruction quality or physical-device readiness. Those remain separate gates.
