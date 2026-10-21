# Engineering APK c11234b5

RealityForge engineering APK — source c11234b5

This is NOT a finished, production-signed or device-qualified release.
Normal release optimization is enabled; instrumentation QA retention is OFF.
Includes sparse PLY export compatibility and defensive validation fixes, plus prior cancellation/cleanup/admission/SIFT fixes.
731 JVM and 12 HOST controller/Room/export tests pass; native frontend/PLY integration and nine parser cases also pass; Android device/full scanning journey qualification remains blocked.

Download all three numbered parts into the same folder, keeping their exact names.
Windows 11: open Command Prompt in that folder and run this single line:

copy /b realityforge-c11234b5-engineering.zip.001+realityforge-c11234b5-engineering.zip.002+realityforge-c11234b5-engineering.zip.003 realityforge-c11234b5-engineering.zip

Then right-click the assembled .zip and choose Extract All. The extracted .apk is the Android installer.
Alternatively, an archive tool supporting split ZIP streams can open .001 with the other parts beside it.
Do not try to install a numbered ZIP part itself.

macOS/Linux: open Terminal in the folder with all three numbered parts and run:

cat realityforge-c11234b5-engineering.zip.001 realityforge-c11234b5-engineering.zip.002 realityforge-c11234b5-engineering.zip.003 > realityforge-c11234b5-engineering.zip

Then extract the assembled ZIP using your normal archive app. This requires no new tool installation.

APK SHA-256:
e2a8b83a5e488cc066f08c80646bab88b0287fdc4ba7409b8ecac8e3d55b296d
APK bytes: 106220465

Package/version: com.realityforge.scanner / versionCode 19.
The test signing certificate matches the retained earlier preview, so replacement without uninstall is expected, but installation/data migration was NOT tested.
Do not uninstall an existing build or delete scans. Preserve/export existing scans first if you choose to test.
This test key is not a production identity. The build is provided for engineering evaluation only.

Source: https://github.com/cheshmakzanoon-ops/3d-scanner/commit/c11234b5b946b27fecbaedb983a606b4393ad6a8

Build and download integrity evidence: [report](../../reports/cloud-ply-engineering-apk.json).
