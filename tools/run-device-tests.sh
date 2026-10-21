#!/usr/bin/env bash
# Uses prebuilt candidate APKs if supplied. Every adb command binds one serial.
set -euo pipefail
if [[ $# -eq 0 ]]; then
    ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
fi
exec python3 tools/device/compatibility/runner.py "$@"
