# Installed candidate matrix

Build APKs first. Run `bash tools/run-device-tests.sh --serial SERIAL --app-apk APP.apk --test-apk TEST.apk --expect-api 36 --expect-abi arm64-v8a --expect-page-size 16384 --output reports/device/SERIAL`.
The full existing test suite supplies actual offline OpenCV/JNI, import, reconstruction/export, and GPU shader/model cases; the runner requires a nonzero instrumentation summary and a fresh validated full-session screenshot. Failed install/test/pull/cleanup exits nonzero. APK SHA-256, exact fingerprint, serial, API, ABI and **measured** page size accompany the result. Devices are never uninstalled by this runner; use only disposable test installations.

Required release environments: API29 x86_64/4KB (minimum), API35 x86_64/4KB (intermediate), API36 x86_64/4KB (target), API35+ arm64-v8a/16KB, and physical ARM64 camera/GPU qualification. API35 16KB ARM64 emulator image: `system-images;android-35;google_apis_ps16k;arm64-v8a`, on a compatible ARM64 host. Verify `adb -s SERIAL shell getconf PAGESIZE` actually returns 16384. If that image/host is absent, record BLOCKED_EXTERNAL; do not infer runtime support from ELF/ZIP alignment. Candidate split installation must be performed with `adb -s SERIAL install-multiple -r ...` and hashes of all splits attached before equivalent package tests; this debug APK runner alone does not certify candidate AAB/splits.

An emulator PASS records only that runtime environment. Physical capture remains NOT_RUN in this receipt and is provided by Agent086. Missing required rows block release acceptance.
