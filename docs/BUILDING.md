# Build and run

## Environment and verified build history

CMake, GCC, Clang, Java/JDK 21 and Python/OpenCV were available. The native core and host
regressions were compiled and executed. The Android SDK, NDK, Gradle and emulator were not
installed. External download attempts failed at host/DNS resolution. M01 later compiled successfully on GitHub Actions, including Android lint, unit tests
and APK structure checks. M02 has separate compilation and runtime gates. A build failure at dependency download does
not prove Android source compilation is correct; compiler/integration fixes may still be needed.

## Android prerequisites

Use Android Studio with a JDK capable of running Gradle 8.13 (JDK 17 is the CI default),
SDK Platform 36, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1. The versions are
pinned for a reproducible initial build rather than claimed to be the newest available.

```text
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358" "cmake;3.22.1"
```

Review/accept the SDK license prompts on the build machine. Android Studio can populate
`local.properties` with the local SDK path; do not commit that file. The previous standalone
Gradle 9.x setup is not used by this project: its wrapper selects Gradle 8.13.

### Wrapper bootstrap

The official Gradle wrapper JAR is not in this source package because its binary could not
be materialized in the workspace. The small `gradlew` / `gradlew.bat` launchers first run
`tools/bootstrap-wrapper.sh` or `.ps1` only when the JAR is absent. They retrieve the official
Gradle 8.13 wrapper from Gradle's repository and compare it with the checksum published by
Gradle. They also install the distribution checksum in the wrapper properties.

The bootstrap requires internet, curl plus sha256sum/shasum on macOS/Linux, or PowerShell
on Windows. The Bash launcher passed syntax checks; the PowerShell script was not executed.
Network/bootstrap success is not claimed. As an alternative, an installed Gradle 8.13 can
run `gradle :app:assembleDebug` directly, or generate an official wrapper itself.

### Build commands

macOS/Linux:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Expected output **after a successful build**:
`app/build/outputs/apk/debug/app-debug.apk`.
That path is not an existing APK in this package.

Then, with an authorized Android device connected:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest
```

The source targets arm64-v8a and x86_64, Android 10/API 29 and later. Camera permission is
optional. Start with a local project and import 2–12 overlapping photographs. Select the
fallback 35 mm-equivalent focal length only when actual EXIF data is unavailable. This
fallback is an assumption, not a calibrated camera. Press **Build sparse 3D preview**.

M02 keeps processing in the visible app and cancels on leaving it. Finished feature caches
can be reused. It does not implement the long-running foreground-service architecture
planned for dense reconstruction. No RAW or ARCore capture mode is shipped yet.

## Validate the actual APK

```bash
python3 tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME"
```

This checks merged INTERNET permission, 16 KB ZIP alignment and LOAD-segment alignment of
all native libraries, including third-party binaries. Source linker flags alone are not
proof that the final package supports every required page size. CI fails these checks when
an incompatible dependency is found; such a dependency must be upgraded or rebuilt.

## Host native tests

```bash
cmake -S . -B build/host -DCMAKE_BUILD_TYPE=Release
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python3 -m pip install -r tools/requirements.txt
python3 -m pytest tests -q
```

For Windows multi-configuration builds, use `--config Release`; the Python bridge searches
`build/host/Release` as well. MSVC compilation has not been tested here. On Linux/macOS,
C++ sanitizer checks are available with `-DRF_SANITIZE=ON`.

## CI

`.github/workflows/verify.yml` supplies native tests, Android debug/test APK compilation,
JVM tests, lint, native alignment/privacy checks, and opt-in emulator instrumentation.
It contains no repository-specific identifiers or credentials. It has not been executed.
Uploading it to a repository and running it is a separate action; none was taken here.

## M02 additional-camera pose test

```bash
python3 tools/three_view.py
```

It uses three local, explicitly synthetic photos and the shared native C++ implementation
for stereo initialization plus calibrated third-view PnP. This does not generate a mesh.
The on-device Android SIFT/pose adapter still requires camera and emulator tests.
