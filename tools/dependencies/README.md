# Dependency gates

Export actual Gradle resolution, including artifacts and module-only platforms:

```
gradle -I tools/dependencies/export.gradle :app:exportDependencyInventory
python tools/dependencies/inventory.py verify build/dependencies/resolved.json --inventory tools/dependencies/evidence/inventory.json
```

`export.gradle` activates dependency locking on release compile/runtime and KSP
processor configurations. 004 must copy `gradle/dependency-locks/app.lockfile` to
`app/gradle.lockfile` and wire the resolution convention (095-to-004). The committed
`gradle/verification-metadata.xml` verifies actual dependency/plugin artifacts with
SHA256; do not run write-verification-metadata during normal acceptance, which would
approve new bytes. The original bootstrap was executed with Gradle8.13/JDK17/AGP8.11.1.

For a reviewed graph update, run export with --write-locks and
--write-verification-metadata sha256, review upstream origins/deltas, then generate:

```
python tools/dependencies/inventory.py generate build/dependencies/resolved.json --cache "$GRADLE_USER_HOME/caches/modules-2/files-2.1" --output tools/dependencies/evidence --notices app/src/main/assets/third-party-notices
python tools/dependencies/assemble_notices.py --opencv-sdk /path/opencv-4.12.0-android-sdk.zip --ndk "$ANDROID_HOME/ndk/28.2.13676358"
python tools/dependencies/inventory.py audit /path/candidate.apk --inventory tools/dependencies/evidence/inventory.json --notices app/src/main/assets/third-party-notices --report /tmp/package-notices.json
```

AABs are audited with the same command. Every full notice file is byte-checked in the
candidate; native GNU build IDs must match resolved Android archives. OpenCV SDK and
upstream full-text downloads use committed expected hashes; a changed response fails.
Source/generated evidence stays small; archives, wheels and APKs remain outside Git.

Host setup is CPython3.12 Linuxx86_64. Use `pip install --require-hashes -r
tools/requirements.lock`; the 14-wheel resolution was installed from an empty venv
and `pip check` plus dependency gate regressions exercised there. This platform lock
must be regenerated and reviewed for another Python/platform, never silently relaxed.
