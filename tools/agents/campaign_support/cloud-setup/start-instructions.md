Use the existing isolated checkout at /workspace/3d-scanner. Do not create a Git worktree unless the user explicitly requests one. Read AGENTS.md and its referenced project documents before development. Keep runtime reconstruction offline.

In each shell:
```bash
cd /workspace/3d-scanner
source /workspace/.setup/3d-scanner/env.sh
```
The retained virtualenv includes CMake, Ninja, pytest9.0.3, OpenCV, NumPy and the reviewed hashed host dependencies. The environment host lock supersedes the legacy direct pytest8.4.1 pin; repository-owned declarations remain unchanged until owner004 applies its handoff. The retained SDK has API36, Build Tools36.0.0, NDK28.2.13676358 and Android CMake3.22.1. Gradle8.13 avoids modifying tracked wrapper properties; JDK17 runs the build.

Host build and readiness:
```bash
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q --junitxml=/workspace/.setup/3d-scanner/pytest.xml
```
Android compilation, JVM tests, lint and APK validation:
```bash
python /workspace/.setup/3d-scanner/gradle.py :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME" --report /workspace/.setup/3d-scanner/apk-validation.json
```
The Gradle helper derives proxy options from the current environment without storing credentials. ANDROID_USER_HOME and GRADLE_USER_HOME must stay in writable /workspace locations. No background service is required. Installed files survive publication; processes do not.

Optional reconstruction gates:
```bash
python tools/benchmark_multiview_coverage.py --gate --output /workspace/.setup/3d-scanner/multiview-coverage.json
python tools/benchmark_texture_middlebury.py --gate --output /workspace/.setup/3d-scanner/middlebury-texture.json
```
Keep synthetic and real-stereo outcomes distinct from physical whole-object quality. This environment has no /dev/kvm or connected handset. Run instrumentation only after an authorized compatible device or emulator is available; do not claim device behavior from host tests.

Do not change tracked source, tests, manifests or lockfiles during environment refresh. Put local reports outside the checkout, and inspect git status before and after. To refresh setup, run bash /workspace/.setup/3d-scanner/install.sh; it verifies official download checksums and preserves TLS verification.


## Optional isolated software Android emulator

The campaign installed official Android emulator37.2.12 (build16428233) and API29 default x86_64 image revision8 in /workspace/campaign/emulator-sdk. Existing shared /workspace/android-sdk versions were preserved. Official SDKmanager used TLS and repository checksum verification. The retained metadata, binary hashes and initial boot/ANR diagnostics are under /workspace/campaign/evidence/092/emulator-probe. Missing /dev/kvm does not prevent an explicit -accel off diagnostic boot; it makes this CPU-only emulator slow and does not establish hardware qualification.

The tested command uses -accel off -gpu swiftshader -cores 1 -memory 2048 -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics, cameras disabled, explicit port5580/serialemulator-5580, and ANDROID_HOME/ANDROID_SDK_ROOT pointing to the isolated SDK. ANDROID_USER_HOME, ANDROID_EMULATOR_HOME and ANDROID_AVD_HOME stay below /workspace/campaign/emulator-user. Never repurpose HOME or disable TLS/checksum/network/security protections.

Processes do not survive environment publication/restoration. Reuse a currently running authorized emulator rather than starting a second one on the same port. All device operations, candidate installs, instrumentation, radio changes and shutdown must coordinate through /workspace/campaign/device-5580.lock. The current reusable guardian implementation is /workspace/campaign/evidence/092/emulator-runtime/boot-software-emulator.py: it holds that lock through bounded360s boot, releases it while idle, and reacquires it before shutdown. It has a bounded3hour lifetime and supports a stop-requested file in its evidence directory. Do not hold the device lock around the guardian's entire lifetime because test clients need it after boot.

For a future run, preserve historical evidence by copying that guardian to a fresh timestamped directory below /workspace/.setup/3d-scanner/emulator-runs and changing only its base=pathlib.Path(...) assignment to that new directory. Run the copy with the existing Python environment after confirming port5580 is unused under the device lock. Keep the selected device serial explicit on every adb call. Confirm BOOTED in its boot-receipt.json, sys.boot_completed=1, package-manager responsiveness, API29/x86_64/4096-page identity and a fresh decoded framebuffer before app tests. Initial cold boot was measured at196.51s; SystemUI ANR/slow input occurred and is retained as a limitation. Boot success is not UI readiness or instrumentation PASS.

Run only the requested immutable candidate app/test APK pair, verify installed hashes, and use the checked-in strict092 debug/release suite manifest. Preserve actual raw pass/skip/fail counts, screenshots and failures. Software API29 execution does not qualify physical capture, ARM64,16KB pages, sustained resources or release gates. Stop only the guardian/emulator started for this workflow by writing stop-requested in that run's evidence directory after all device intervals finish; it waits for the device lock before cleanup. Never remove user projects, reset an existing installation or fill storage indiscriminately.

For authorized campaign worktrees, use the current-directory serialized /workspace/campaign/gradle.py helper with /workspace/campaign/android.lock, rather than the primary-checkout-only setup helper. Use existing worktrees; do not create or switch checkouts unless the user requests it.
