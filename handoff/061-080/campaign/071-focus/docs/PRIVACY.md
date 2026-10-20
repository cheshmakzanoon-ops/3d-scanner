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
