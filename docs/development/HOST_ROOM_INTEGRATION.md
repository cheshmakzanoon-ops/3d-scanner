# HOST Room integration (not device qualification)

Run both independent gates with JDK 17 and the reviewed Gradle wrapper:

```sh
./gradlew --no-daemon --max-workers=1 --no-parallel :app:testDebugUnitTest
./gradlew --no-daemon --max-workers=1 --no-parallel :app:hostIntegrationTest
```

The ordinary JVM gate excludes `com.realityforge.scanner.hostintegration` and does
not resolve the SDK. The explicit HOST gate compiles the same production JVM code,
uses generated Room DAOs and AOSP native SQLite in Robolectric, and does not start
an Android guest, application composition root, reconstruction JNI or GPU.
Tests use a basic Application and no app manifest. Synthetic metadata/bytes are
not decoder or real-camera evidence. A successful HOST test does not close any
physical-device, provider permission/Binder, APK/R8, native reconstruction,
Android filesystem/power-loss, or release qualification gate.

`robolectricSdk` is a separately locked, non-transitive test-only configuration:
`org.robolectric:android-all:15-robolectric-12650502`. Gradle strict dependency
verification applies before its canonical jar is staged under
`app/build/robolectric-sdk`. Robolectric 4.14.1 then uses only that directory,
with offline resolution always enabled and preinstrumented SDKs disabled. Its
internal Maven downloader is not used. There is no `/tmp` path or user-specific
cache dependency. Initial Gradle resolution needs official Maven access; normal
Gradle `--offline` works after all locked artifacts have been cached. Do not
turn off verification, add broad trusted-artifact rules, or supply an alternate
`robolectric-deps.properties` mapping.

First framework resolution costs approximately 177 MiB download plus the build
staging copy; Gradle caches are reusable. The HOST task is limited to one fork,
1 GiB Java heap and a five-minute task timeout; individual tests have a two-minute
bound. These are infrastructure bounds, not altered device-test deadlines.
Source classification rejects Robolectric imports outside the dedicated package.
Do not move HOST tests into the ordinary JVM gate or add SDK dependencies to APK
configurations. CI explicitly runs both gates and retains their separate XML/HTML
report paths. Missing/filtered HOST tests must not be reported as a pass.

Classify dependency resolution, Robolectric/SQLite initialization and timeout
failures as HOST infrastructure failures. Classify assertions reached after
successful initialization as tested application behavior. Report test counts,
source SHA and exact gate separately from any device evidence.

Build outputs can be removed after results are verified and source is committed
and pushed; never commit downloaded jars, compiled tests or machine paths.
