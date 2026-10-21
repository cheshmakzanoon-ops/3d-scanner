RealityForge engineering APK — source e67e65e8

This is NOT a finished, production-signed or device-qualified release.
Normal release optimization is enabled; instrumentation QA retention is OFF.
Includes cancellation/cleanup/admission fixes and the verified shared SIFT core with native-error preservation.
722 JVM, 10 HOST controller/Room, 10 SIFT and eight matcher HOST JNI tests pass; Android device/full scanning journey qualification remains blocked.

Download all three numbered parts into the same folder, keeping their exact names.
Windows 11: open Command Prompt in that folder and run this single line:

copy /b realityforge-e67e65e8-engineering.zip.001+realityforge-e67e65e8-engineering.zip.002+realityforge-e67e65e8-engineering.zip.003 realityforge-e67e65e8-engineering.zip

Then right-click the assembled .zip and choose Extract All. The extracted .apk is the Android installer.
Alternatively, an archive tool supporting split ZIP streams can open .001 with the other parts beside it.
Do not try to install a numbered ZIP part itself.

macOS/Linux: open Terminal in the folder with all three numbered parts and run:

cat realityforge-e67e65e8-engineering.zip.001 realityforge-e67e65e8-engineering.zip.002 realityforge-e67e65e8-engineering.zip.003 > realityforge-e67e65e8-engineering.zip

Then extract the assembled ZIP using your normal archive app. This requires no new tool installation.

APK SHA-256:
5ea0eb4053e4d59dcafa87c14b6ca1cfb86d33e048a8452f1249caab30cca572
APK bytes: 106220465

Package/version: com.realityforge.scanner / versionCode 19.
The test signing certificate matches the retained earlier preview, so replacement without uninstall is expected, but installation/data migration was NOT tested.
Do not uninstall an existing build or delete scans. Preserve/export existing scans first if you choose to test.
This test key is not a production identity. The build is provided for engineering evaluation only.

Source: https://github.com/cheshmakzanoon-ops/3d-scanner/commit/e67e65e800a4f591b9f1defa648842e30ad5c88a
