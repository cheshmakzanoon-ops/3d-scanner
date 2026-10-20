# Build and verify RealityForge

Use the committed Gradle wrapper for local and CI Android builds. The first build needs
access to the pinned Gradle distribution, Android SDK and Maven artifacts. The installed
application has no Internet permission and performs reconstruction locally.

## Pinned toolchain

| Component | Version / setting | Source |
| --- | --- | --- |
| Gradle | 8.13 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.11.1 | `build.gradle.kts` |
| JDK / Java target | 17 | CI and `app/build.gradle.kts` |
| Kotlin / Compose compiler | 2.1.21 | `build.gradle.kts` |
| KSP | 2.1.21-2.0.1 | `build.gradle.kts` |
| Compile / target SDK; minimum SDK | 36; 29 | `app/build.gradle.kts` |
| Build Tools | 36.0.0 | `app/build.gradle.kts` |
| NDK | 28.2.13676358 | `app/build.gradle.kts` |
| Android CMake | 3.22.1 | `app/build.gradle.kts` |
| Packaged ABIs | arm64-v8a, x86_64 | `app/build.gradle.kts` |
| Room / Android OpenCV | 2.7.1 / 4.12.0 | `app/build.gradle.kts` |
| Host benchmark OpenCV | 4.12.0.88 | `tools/requirements-real-benchmark.txt` |

These pins define the integration target, not a claim that they are the newest releases.
Dependency repositories are centrally restricted to Google, Maven Central and the Gradle
plugin portal. The app remains one Android module with explicit native sources/test targets.

## Wrapper integrity and prerequisites

```bash
python3 build-logic/verify_wrapper.py
./gradlew --version
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
```

The official wrapper JAR is committed with SHA-256
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
Its embedded Apache 2.0 license is in `gradle/wrapper/LICENSE`. The distribution checksum is
`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
Provenance: [Gradle checksums](https://gradle.org/release-checksums/) and
[the official wrapper](https://github.com/gradle/gradle/tree/v8.13.0/gradle/wrapper).
The verifier checks the JAR, version and distribution pin offline.

Use JDK 17 and set `ANDROID_HOME`, or let Android Studio write ignored `local.properties`.
Accept SDK licenses on the build machine. Do not commit SDKs, NDKs, dependency caches,
build outputs or signing keys. No globally installed Gradle is required.

If the wrapper JAR is missing/corrupt, restore it from Git. `tools/bootstrap-wrapper.sh`
and `.ps1` are explicit repair helpers that verify the pinned checksum before replacement.
Normal wrapper builds do not invoke these helpers. A download failure does not prove
source compilation; investigate checksum mismatches instead of bypassing them.

## Android compile, tests and package checks

```bash
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
./gradlew --no-daemon :app:assembleRelease :app:lintRelease
python3 tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME"
python3 tools/check_apk.py app/build/outputs/apk/release/app-release-unsigned.apk --sdk "$ANDROID_HOME"
```

Windows uses `gradlew.bat` with the same task names. Platform-specific commands are not
records of execution; see the audit receipts for actual evidence.

The configured Gradle heap is 3 GiB. Reduce parallel workers first on constrained hosts:
the integrated Compose compiler exhausted a 512 MiB heap during the 2026-10-20 audit.
CI uses the checked-in configuration. Static source checks never replace compilation.

Debug, instrumentation and unsigned release APKs have separate outputs. Synthetic photo
fixtures, migration schema assets and the shared fault provider exist only in `androidTest`.
`check_apk.py` rejects fixture assets in production APKs and checks merged Internet permission,
16 KiB ZIP alignment and ELF LOAD alignment for every native library, including OpenCV.
Source linker flags alone do not qualify the final package.

Release is currently unsigned and R8 is disabled. Signing, shrinking/JNI qualification,
distribution notices and physical release-device qualification remain separate release-owner
gates; building `assembleRelease` does not mark them complete.

## Android runtime checks

With an authorized disposable emulator or test device:

```bash
bash tools/run-device-tests.sh
```

This invokes the wrapper's `connectedDebugAndroidTest`. Tests exercise actual Room migration,
JNI, synthetic photographic reconstruction, provider streams, recoverable publication and
the shared DocumentsProvider fault matrix. Faults affect bounded test-owned files; they do
not fill a user's filesystem. Synthetic photographs are not physical capture evidence.

Execution uses durable job attempts, a foreground service and pause/cancel/resume. Process
restart evidence must involve a real process boundary. Camera vendor behavior, physical
thermals, background restrictions, installed-build upgrades and ARM64 qualification require
their own recorded device runs.

## Host checks

```bash
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python3 -m pip install -r tools/requirements-real-benchmark.txt
python3 -m pytest tests -q
```

Host tests link the real native core. JNI is included only for Android and host executables
are never packaged. Supported host sanitizers use `-DRF_SANITIZE=ON`. Windows multi-config
builds need `--config Release`; unexecuted MSVC/sanitizer runs remain unverified.

## CI and evidence

[Verify RealityForge](../.github/workflows/verify.yml) runs host tests and retained real/synthetic
numerical benchmarks; Android debug/instrumentation/release compilation, JVM tests, lint and
APK checks; and API 35 x86_64 emulator instrumentation on main pushes. Emulator tests can
also be requested through workflow dispatch. Jobs retain actual reports and built APKs.
Every Android CI command uses the committed wrapper.

[The agents 001–030 audit](agents/AUDIT_001_030.md) and per-agent receipts identify the actual
fixes, executed checks and outstanding gates. Historical milestone reports describe their
own source revision and are not proof for a later integration tree.

Follow the staged-blob source-manifest protocol before committing:

```bash
git add <the intended changes>
python3 build-logic/update_source_manifest.py
git add SOURCE_MANIFEST.sha256
python3 -m pytest tests/test_project_contracts.py -q
```

The generator hashes stage-0 Git blobs and lists every tracked file except the manifest
itself. Resolve merges and stage new files before generating it.
