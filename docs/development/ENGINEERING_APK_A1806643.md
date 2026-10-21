RealityForge engineering APK — source a1806643

This is NOT a finished, production-signed or device-qualified release.
Normal release optimization is enabled; instrumentation QA retention is OFF.
Includes callback-cancellation, native-operation cleanup, job-admission and running-job identity fixes.
722 JVM, 10 HOST controller/Room and13 HOST native-wrapper/helper tests pass; Android device/full scanning journey qualification remains blocked.

Download all three numbered parts into the same folder, keeping their exact names.
Windows 11: open Command Prompt in that folder and run this single line:

copy /b realityforge-a1806643-engineering.zip.001+realityforge-a1806643-engineering.zip.002+realityforge-a1806643-engineering.zip.003 realityforge-a1806643-engineering.zip

Then right-click the assembled .zip and choose Extract All. The extracted .apk is the Android installer.
Alternatively, an archive tool supporting split ZIP streams can open .001 with the other parts beside it.
Do not try to install a numbered ZIP part itself.

macOS/Linux: open Terminal in the folder with all three numbered parts and run:

cat realityforge-a1806643-engineering.zip.001 realityforge-a1806643-engineering.zip.002 realityforge-a1806643-engineering.zip.003 > realityforge-a1806643-engineering.zip

Then extract the assembled ZIP using your normal archive app. This requires no new tool installation.

APK SHA-256:
cee7ccd79c26548875d9a50a3bc808ab34676f15e1adf4378f8ce5bb3c1f9ef6
APK bytes: 106220465

Package/version: com.realityforge.scanner / versionCode 19.
The test signing certificate matches the retained earlier preview, so replacement without uninstall is expected, but installation/data migration was NOT tested.
Do not uninstall an existing build or delete scans. Preserve/export existing scans first if you choose to test.
This test key is not a production identity. The build is provided for engineering evaluation only.

Source: https://github.com/cheshmakzanoon-ops/3d-scanner/commit/a1806643fea5250c3e6cc85ed277be022bfa66a5
