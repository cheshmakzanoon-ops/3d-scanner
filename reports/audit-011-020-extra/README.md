# Additional existing JVM checks for agents 011–020

## Actual result and source

**PASS: 128 existing tests across 33 classes.** The actual Kotlin 2.1.21 compiler
completed successfully; JUnit 4.13.2 reported `OK (128 tests)` in 3.505 seconds.
The runner verified every dependency checksum and each selected production/test
file remained unchanged during execution. All selected bytes were independently
compared with local code candidate `54ae92a822693b439452cf53852bc4c30150002a`,
whose published exact tree is represented by GitHub commit
`aad8e1d5754491d8f0e6a0aca96a9f1f02f53ba6`.

This is supplementary real JVM execution of capture/import contracts, image
orientation and bounds, capture controls and quality policies, curation state and
commands, and portable cache inventory/retention/recovery. Existing test fixtures
exercise their actual production ports. It is not a full Android Gradle,
CameraX/OpenCV, Room, instrumentation, or physical-device result.

The prior 44-test core batch and 67-test additional 001–010 batch were not rerun.
`provenance.json` gives the exact 45 production sources, existing test/helper
files, 33 executed classes, all source/dependency hashes, compiler invocation,
runtime classpath, and source-stability result. `runner.py` is the exact executed
runner. Both JVM processes used `-Xmx512m` and an isolated temporary directory.

## Dependency boundary

The first complete-source selection also contained `SavedPhotoGuidanceSession`.
Its production defaults directly invoke the actual OpenCV adapter, which is not
part of this portable runtime. That incomplete portable closure did not compile;
its real diagnostics are retained in `initial-closure-compile.log`. The source
and its test were excluded from the corrected portable batch without replacing
or editing the production implementation. They remain registered for Android CI.

Agent 019 tests, `GlobalCacheInventoryServiceTest`, and
`CacheDescriptorBindingsTest` likewise require their actual Room/application
source closure and remain in full Gradle verification. No Android, Room, or
OpenCV substitute was introduced to obtain this supplementary pass.

The successful compiler emitted an existing Kotlin data-class constructor/copy
visibility warning for `CaptureAttempt`; the complete warning is preserved.
The pinned compiler accepts it. Text-log copies normalize trailing line whitespace and blank final
lines; provenance retains both original captured and stored log hashes.

## Evidence

- `compile.log`: successful actual compiler output.
- `junit.log`: actual 128-test result.
- `provenance.json`: exact sources, dependencies, invocation, and artifact hashes.
- `runner.py`: exact executed local evidence harness; the normal Android build
  remains the committed Gradle wrapper.
- `initial-closure-compile.log`: explicit excluded OpenCV dependency boundary.
