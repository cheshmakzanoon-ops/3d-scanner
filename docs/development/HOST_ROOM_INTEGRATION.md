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

## Early reconstruction admission

The admission HOST tests use the actual reconstruction controller, repository, and generated Room
DAOs. They reject noncanonical production operation identifiers before durable enqueue, retain
canonical UUID enqueue behavior, and verify that generic-store-safe but execution-incompatible
persisted request IDs fail without retaining a RUNNING lease or attempt state. These inputs are
valid under the general durable-job contract, not necessarily corrupted records.

The fixture explicitly drives Robolectric's paused main looper for controller notification recovery
and stops/joins its private process-lifetime scope through test-only reflection before closing Room.
No controller algorithm is mocked and no production shutdown API is added. These early-failure tests
do not decode images, allocate a reconstruction native token, run the scanner pipeline, or provide
service/device qualification. Startup/framework failures remain HOST infrastructure failures.

The third admission test injects a different running identity after the actual failure callback,
then joins the worker and verifies cleanup preserves that identity. This is deliberate fencing
coverage, not evidence of naturally overlapping workers. The paired normal-cleanup assertion
regresses a boxed inline JobId identity-CAS bug; value-aware atomic cleanup must clear the equal ID.


## Native-graphics decoder integration (HOST only)

The normal `:app:hostIntegrationTest` gate also exercises the unchanged production
`AndroidImageDecoder` with API35 Robolectric native software graphics and the three
committed opaque synthetic relief JPEGs. It checks JPEG MIME/dimensions, full-raster
opacity/nonblank contrast, planned sampling and scaled dimensions at edges1600/256/512,
source hashes, bitmap recycling and pixel-position agreement. A private EXIF copy
checks the actual90-degree transform without changing committed inputs.

`generateHostDecoderOracle` selects a JDK17 toolchain (not an Android compile API)
and uses standalone `tools/GenerateDecoderOracle.java` to generate independent
ImageIO/bilinear RGB references. It has a256MiB heap and120-second task bound.
Exact three JPEGs, reviewed generator source, Java executable and runtime version
are task inputs; about6.53MB of references live only in `app/build/host-decoder-oracle`,
never APK assets or committed binaries. Invalidation recreates only that dedicated
output directory, rejecting aliased paths/symlinks before cleanup. No new packages
or downloads are required when the pinned test dependencies and JDK17 are cached.

An always-run verifier checks pinned input/source identity and all generated output
hashes and lengths. The manifest records generator SHA, JDK runtime and RGB layout;
each reference header also binds source SHA and dimensions. The HOST test receives
the verified manifest hash, rejects incomplete/changed references, and compares
actual decoder pixels to references with the original fixed mean absolute RGB error
bound of12.0. References never replace production decoded pixels. The HOST process
alone opens `java.base/java.io` for pinned Robolectric's FileDescriptorInterceptor;
no ordinary JVM gate, production module or release dependency graph is changed.

Provenance: the first temporary probe failed Android Kotlin compilation because
AWT/ImageIO are not Android APIs. Moving only the independent oracle to standalone
JDK compilation fixed that boundary. The next run reached Robolectric but failed
FileDescriptor reflection access before pixel assertions. The narrowly scoped java.io
opening then yielded actual2/2 passing prerequisite tests in6.03seconds. These failures
remain infrastructure evidence, not hidden retries or successful decoder assertions.
Permanent task wiring requires its own actual gate result; static contracts are not
framework execution. This is HOST software decode/transform evidence, not Android
hardware decode, permissions, GPU, full reconstruction or device qualification.
