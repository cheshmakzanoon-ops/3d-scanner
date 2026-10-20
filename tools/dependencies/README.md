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

## Full host QA used by093 CI

```
python tools/dependencies/install_qa.py --root "$RUNNER_TEMP/rf-qa" --github-env "$GITHUB_ENV" --github-path "$GITHUB_PATH"
```

The root must be outside the checkout. Following CI steps receive an isolated Python
with all15 pinned/hashed scientific+pytest+Trimesh packages, verified Node24.19.0,
Blender4.3.2 on PATH, RF_GLTF_VALIDATOR (exact Khronos2.0.0-dev.3.10), and
RF_SECURITY_PYTHON/SCANNER_PYTHON pointing to a separate31-package hashed scanner venv.
`ROOT/installation.json` records actual versions, tool/archive hashes and lock hashes;
`ROOT/activate.sh` supports local shells. No source file, original base pin, app runtime
classpath or system Python is changed. Optional --qa-wheelhouse and
--security-wheelhouse enable fully hash-verified offline Python installation.

Node's committed archive SHA256 comes from its official SHASUMS manifest; Khronos's
exact archive SHA512 comes from the resolved npm integrity record. Blender reuses an
already-installed exact4.3.2 executable and records its binary hash, or downloads the
official portable4.3.2 archive and verifies its exact filename entry against Blender's
authoritative release SHA256 manifest before extraction. --require-portable-blender
forces that path. Tar extraction uses Python's safe data filter and cached corrupted
artifacts fail closed. There is no verification bypass.

An empty fresh setup of both Python environments plus verified Node/Khronos and the
existing system Blender4.3.2 passed the real090 interoperability and096 scanner suites:
**25 tests passed in8.08s**. The cloud proxy denies download.blender.org, so the fresh
portable Blender download branch could not run here; current functional evidence uses
the recorded installed system binary, not an invented portable archive hash. CI needs
HTTPS access to download.blender.org, nodejs.org, registry.npmjs.org, pypi.org and
files.pythonhosted.org. Publisher-checksum matching, corruption rejection and archive
escape rejection have independent regression tests.

Benchmark-only46-module dependency evidence lives in evidence/benchmark. Test compile/
runtime graphs live in evidence/tests. They are distinct from shipped123-module app
inventory. The generated macrobenchmark.lockfile and complete app.lockfile are copied
by004 to their matching module paths; verification metadata includes these actual QA
artifacts. ErrorProne2.26.1 is compile-only test metadata; real CLASS-retention annotation
classes resolve testR8 without shipping JDK compiler model types. Official bundletool
1.18.2 is an external Apache2.0 QA tool with pinned hash and preserved license text.


Pytest is pinned to9.0.3 in both owned scientific/QA locks after actual pip-audit
identified PYSEC-2026-1845 in8.4.1. The exact wheel hash is independently verified
against PyPI9.0.3 metadata. Direct full-QA inputs are qa-requirements.in; all existing
other distribution pins remain unchanged. Use the hashed lock for CI bootstrap, not
the older004 input pending its source-owner update. App/runtime graph is unchanged.

Direct004 integration preserves debug instrumentation by default. Keep
`-I tools/dependencies/export.gradle --dependency-verification strict` on ordinary
build invocations so the committed combined module lock enforces both variants.
The exporter activates only the listed main/KSP/unit/instrumentation configurations
when AGP actually creates them. Export debug main with `-PrfInventoryScope=debug`
and debug tests with `-PrfInventoryScope=debug-tests`; export release tests with
`-PrfInventoryScope=tests -PrealityForgeTestBuildType=release`. Requested absent
configurations or unknown scopes fail rather than yielding a partial approved graph.
Debug supplemental SBOM scopes remain QA-only and do not enlarge shipping notices.
