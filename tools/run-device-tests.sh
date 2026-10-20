#!/usr/bin/env bash
# AGP may uninstall the app before Gradle returns. The instrumented test creates
# this synthetic screenshot using UiAutomation's shell identity outside app data.
# Validate actual PNG bytes: adb exec-out may return success for remote errors.
set -u
status=0
adb -e shell rm -f /data/local/tmp/realityforge-m12-session.png
./gradlew --no-daemon :app:connectedDebugAndroidTest || status=$?
mkdir -p reports/device
if ! adb -e pull /data/local/tmp/realityforge-m12-session.png reports/device/photographic-viewer.png; then
    rm -f reports/device/photographic-viewer.png
    echo "Full-session photographic screenshot was not produced." >&2
    if [ "$status" -eq 0 ]; then status=1; fi
else
    python3 tools/check_screenshot.py reports/device/photographic-viewer.png || status=1
fi
adb -e shell rm -f /data/local/tmp/realityforge-m12-session.png
exit "$status"
