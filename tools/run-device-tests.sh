#!/usr/bin/env bash
# Keep the test exit status and collect the synthetic full-session screenshot
# before android-emulator-runner shuts the emulator down. No user photo data.
set -u
status=0
gradle --no-daemon :app:connectedDebugAndroidTest || status=$?
mkdir -p reports/device
if ! adb -e exec-out run-as com.realityforge.scanner cat files/m12-test-photographic-viewer.png > reports/device/photographic-viewer.png; then
    rm -f reports/device/photographic-viewer.png
    echo "Full-session photographic screenshot was not produced." >&2
    if [ "$status" -eq 0 ]; then status=1; fi
fi
exit "$status"
