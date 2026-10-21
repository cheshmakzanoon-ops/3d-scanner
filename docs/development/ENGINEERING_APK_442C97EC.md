RealityForge engineering APK — source 442c97ec

This is NOT a finished, production-signed or device-qualified release.
Normal release optimization is enabled; instrumentation QA retention is OFF.
Includes callback-cancellation and native-operation cleanup fixes.
722 JVM, seven HOST Room and13 HOST native-wrapper/helper tests pass; Android device/full scanning journey qualification remains blocked.

Download all three numbered parts into the same folder, keeping their exact names.
Windows 11: open Command Prompt in that folder and run this single line:

copy /b realityforge-442c97ec-engineering.zip.001+realityforge-442c97ec-engineering.zip.002+realityforge-442c97ec-engineering.zip.003 realityforge-442c97ec-engineering.zip

Then right-click the assembled .zip and choose Extract All. The extracted .apk is the Android installer.
Alternatively, an archive tool supporting split ZIP streams can open .001 with the other parts beside it.
Do not try to install a numbered ZIP part itself.

macOS/Linux: open Terminal in the folder with all three numbered parts and run:

cat realityforge-442c97ec-engineering.zip.001 realityforge-442c97ec-engineering.zip.002 realityforge-442c97ec-engineering.zip.003 > realityforge-442c97ec-engineering.zip

Then extract the assembled ZIP using your normal archive app. This requires no new tool installation.

APK SHA-256:
705ea127088b8a0186235aa20c39d0adce1bcad5ee7124c1dac1fd47a2098725
APK bytes: 106220465

Package/version: com.realityforge.scanner / versionCode 19.
The test signing certificate matches the retained earlier preview, so replacement without uninstall is expected, but installation/data migration was NOT tested.
Do not uninstall an existing build or delete scans. Preserve/export existing scans first if you choose to test.
This test key is not a production identity. The build is provided for engineering evaluation only.

Source: https://github.com/cheshmakzanoon-ops/3d-scanner/commit/442c97ec92cdfc15f38cbd4426a49e5461cca3a9
