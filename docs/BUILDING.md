# Build and verify RealityForge

Current application version: **0.19.0-m19**. Integrated source changes require new
exact-source build and device receipts; historical milestones do not qualify this tree.

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
python3 tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME" --report reports/apk-validation.json
python3 tools/check_apk.py app/build/outputs/apk/release/app-release.apk --sdk "$ANDROID_HOME"
```

Windows uses `gradlew.bat` with the same task names. Platform-specific commands are not
records of execution; see the audit receipts for actual evidence.

Use the checked-in Gradle configuration and reduce parallel workers first on
constrained hosts. Static source checks never replace compilation.

Debug, instrumentation and release-candidate APKs have separate outputs. Synthetic photo
fixtures, migration schema assets and the shared fault provider exist only in `androidTest`.
`check_apk.py` rejects fixture assets in production APKs and checks merged Internet permission,
16 KiB ZIP alignment and ELF LOAD alignment for every native library, including OpenCV.
Source linker flags alone do not qualify the final package.

The effective release convention in `app/build.gradle.kts` enables R8 minification
and resource shrinking. `candidateSigning` defaults to `test`, using the debug
signing configuration; this is a **test-signed release candidate**, not production
signing. `-PcandidateSigning=production` requires externally supplied production
credentials and must not be used with committed secrets. Shrinking/JNI runtime
qualification, distribution notices and physical release-device qualification
remain separate gates; `assembleRelease` alone does not complete them.

The [24 October validation checkpoint](MILESTONE_STATUS.md) reports successful APK
builds and 850 unit + 18 HOST JVM tests (868 total), not a successful installed photo-to-model
session. Device preflight is blocked, and both native sanitizer profiles timed
out; consult the exact run before describing an APK as validated.

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
cmake --build build/host --parallel 1
ctest --test-dir build/host --output-on-failure
python3 -m pip install -r tools/requirements-real-benchmark.txt
python3 -m pytest tests -q
```

Host tests link the real native core. The standard host CMake build is not an
Android package, and host executables are never packaged in the app. Separate
HOST JNI checks do not establish device qualification. Supported host sanitizers
use `-DRF_SANITIZE=ON`. Windows multi-config
builds need `--config Release`; unexecuted MSVC/sanitizer runs remain unverified.

## CI and evidence

[Verify RealityForge](../.github/workflows/verify.yml) runs host tests and retained real/synthetic
numerical benchmarks; Android debug/test APK and benchmark builds, JVM tests, lint
and APK checks; and API 35 x86_64 emulator instrumentation when device preflight
succeeds. A configured job is not evidence that its checks passed. Emulator tests can
also be requested through workflow dispatch. Jobs retain actual reports and built APKs.
Every Android CI command uses the committed wrapper.

Test reports identify executed checks and outstanding gates for their source
revision. Historical milestone reports are not proof for a later integration tree.

Follow the staged-blob source-manifest protocol before committing:

```bash
git add <the intended changes>
python3 build-logic/update_source_manifest.py
git add SOURCE_MANIFEST.sha256
python3 -m pytest tests/test_project_contracts.py -q
```

The generator hashes stage-0 Git blobs and lists every tracked file except the manifest
itself. Resolve merges and stage new files before generating it.


## Independent host QA dependencies

The complete Python suite additionally runs real security scanners and independent export
readers. Missing tools produce NOT_RUN, never successful validation. Use isolated Python 3.12
outside the checkout; the pinned, hash-verified installation entry point is:

```bash
python3 tools/dependencies/install_qa.py --root "$HOME/.local/share/realityforge-qa"
```

See `tools/dependencies/qa-tools.json` and the sibling hashed requirements locks for the
external-only QA toolchain: trimesh 4.5.3, Khronos glTF Validator 2.0.0-dev.3.10,
Blender 4.3.2, Bandit 1.8.6, detect-secrets 1.5.0 and pip-audit 2.9.0. These are not
Android runtime dependencies. Set `RF_GLTF_VALIDATOR` to the verified validator package,
`RF_SECURITY_PYTHON` to the isolated scanner interpreter, and put Node and Blender on PATH.

Rebuild `build/host/librealityforge.so` **before** running Python native regressions after
merging native headers/sources. Loading an old library against new C++ structures can cause
ABI failures, not only missing symbols. Never interpret such a run as new-source validation.
Retain CTest/Python output, dependency versions, and source identity with every result.

## Recovery and evidence interpretation

The current admitted reconstruction profile is at most twelve selected photographs;
`PipelineCapacityPolicy` rejects larger target profiles rather than silently truncating them.
Dense processing admits at most eight registered views. These are prototype resource gates,
not proof of complete-object reconstruction. Capture archives may contain more originals
without making the larger reconstruction profiles supported.

Use only authorized disposable test data for interruption tests: import overlapping photos,
record original hashes, reconstruct, pause/kill/reopen, and verify unchanged-input checkpoints
can be reused while changed-input results are rejected. Inspect/export/reopen completed
results and compare original hashes. Do not clear a user's app data to simulate recovery.

Record PASS, FAIL, NOT_RUN or BLOCKED_EXTERNAL for each exact-source gate. A dependency
network failure is BLOCKED_EXTERNAL, not evidence that Android compilation succeeded.
Missing instrumentation, zero executed tests and absent physical-device evidence remain
open gates. Preserve full reports, APK SHA-256, device/API/ABI identity and test counts.
The default test-signed release build does not establish production signing, R8/JNI runtime qualification,
store acceptance, physical 360-degree quality, thermal safety or power-loss durability.
