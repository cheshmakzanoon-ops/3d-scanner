RealityForge engineering APK — source ae756d79

NOT a finished, production-signed or physical-device-qualified release.
Normal release optimization is enabled; instrumentation QA retention is OFF.
Includes notification attempt-fence, depth-checkpoint inventory and PNG stream compatibility fixes, plus earlier PLY export and cancellation/cleanup corrections.
748 ordinary JVM and 17 HOST tests, Android test compilation and lint pass. Broad Python: 1,259 tests and six subtests pass; three optional checks skipped. Previous source515f1435 complete host reconstruction and separate publication/reopen/seven direct export checks passed; they have not been rerun on this notification-only change. Those checks ran using an explicitly restricted opaque Bitmap bridge. This is not physical camera/phone, controller/service admission or real-world scan-quality qualification.

Download all three numbered parts to the same folder, keeping exact filenames.
Windows: open Command Prompt in that folder and run:
copy /b realityforge-ae756d79-engineering.zip.001+realityforge-ae756d79-engineering.zip.002+realityforge-ae756d79-engineering.zip.003 realityforge-ae756d79-engineering.zip
Then right-click the assembled ZIP and choose Extract All. Install only the extracted APK, not any numbered part.

macOS/Linux: open Terminal in that folder and run:
cat realityforge-ae756d79-engineering.zip.001 realityforge-ae756d79-engineering.zip.002 realityforge-ae756d79-engineering.zip.003 > realityforge-ae756d79-engineering.zip
Then extract the ZIP with your normal archive application.

APK SHA-256: 6f09d6149befec4524cc28f06152e5a0c7d51bad2d8349d5c3455cd5430608ec
APK bytes: 106220465
ZIP SHA-256: b680934c468f7da9cdaeae2ff7f2afb71f45b7d9844bc922cca9324e88623a79

Package com.realityforge.scanner, versionCode19. Existing test certificate retained; in-place replacement is expected but installation/data migration has NOT been tested. Do not uninstall or delete existing scans to work around a problem. Preserve/export important scans first. This test key is not a production identity.

Source: https://github.com/cheshmakzanoon-ops/3d-scanner/commit/ae756d79a4880b735a40bfa1ac536ba64d11a5e0
