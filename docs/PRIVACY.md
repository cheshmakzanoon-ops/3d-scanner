# Offline and privacy contract

The first-party Android manifest removes INTERNET and ACCESS_NETWORK_STATE during manifest
merging. No code for an account, network upload, telemetry, advertising or remote inference
is included. OpenCV is a build-time dependency bundled into the intended APK; it is not
fetched as part of scanning. The actual merged APK still has to pass the supplied inspection
and device tests; source configuration alone is not a verified runtime privacy result.

Camera permission is requested only for taking a photograph. Import uses a document picker
and copies the selected input to private storage; broad photo-library/file permissions are
not requested. Originals outside the app are not deleted by project deletion.

OS backup and device-transfer rules exclude app data in source. The implementation does not
add separate application-level encryption or claim protection on a compromised/rooted device.
Project files inherit the device's ordinary application sandbox/storage protections.

User-selected document providers are outside the reconstruction engine. Choosing a cloud
folder to import or export can cause that provider to use a network. That action is different
from the app secretly uploading scan data. For an airplane-mode test, use local source photos
and a local export destination. Build-time Gradle/SDK downloads also are not runtime uploads.

Uninstalling removes app-private originals, caches and unexported outputs. Export important
work first. No cloud recovery is provided. A release privacy policy and distribution-store
disclosures remain work for the release phase, after inspecting the final dependencies/APK.

## Repeatable candidate checks

Agent089's package audit inspects the compiled candidate manifest and binary extraction
rules with SDK aapt2; source XML is not accepted as package evidence:

```sh
python tools/device/offline_privacy/audit.py --apk app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME" --source-sha FULL_CANDIDATE_SHA --output /tmp/privacy-package.json
```

Cloud backup and device transfer each exclude credential-protected and device-protected
root, files, databases and preferences, plus external app data. The application separately
disables backup and cleartext traffic. These packaged policy checks do not prove an actual
OS backup/restore or vendor transfer implementation obeys policy; qualify those on supported
physical handsets before a release privacy claim.

For runtime qualification use an explicitly selected disposable device, the exact app/test
APKs and local synthetic fixtures. Disable mobile data and Wi-Fi and enable airplane mode
before running the controller. It records those settings before and after, installs the
identified candidate only with the explicit --install option, and requires all three
instrumentation tests to complete without skips:

```sh
python tools/device/offline_privacy/journey.py --serial SERIAL --apk app/build/outputs/apk/debug/app-debug.apk --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk --source-sha FULL_CANDIDATE_SHA --install --output /tmp/privacy-journey.json
```

The tests exercise real synthetic import/calibration/reconstruction/model inspection/reopen/
export, absence of network permissions, camera-optional imports, and project deletion while
checking byte identities of external original inputs, prior exports and another project.
Default instrumentation deliberately marks the explicit-radio scenario NOT_RUN unless the
controller marker is supplied; a skipped scenario is never an offline pass. This test uses
synthetic photographs, not physical object evidence. Physical capture and actual OS backup/
transfer remain separate gates. No attached device is available in this cloud instance.

Keep instrumentation logs private: diagnostic output can contain local paths or operator
labels. Publish source/artifact hashes and bounded reports rather than original photographs,
project names or raw device logs. The tooling does not collect or transmit logs remotely.
