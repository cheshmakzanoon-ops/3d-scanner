# Engineering APK e188704d: package validation and delivery

This is a test-signed engineering candidate, not a production or physical-device-qualified release.

## Fixed artifact identity

- Built from clean source commit `e188704ddb4aa6a1a9cace8bf48efeb3910690f9`.
- APK: `realityforge-e188704d-engineering.apk`, 106,220,465 bytes.
- SHA-256: `ee1736e82742246911add0ccddd426ab4de7449f2435322f3c6987714423b4c0`.
- This historical build identity must not be changed if repository history is subsequently rewritten.
- Package `com.realityforge.scanner`; version `0.19.0-m19`, code 19, minimum API 29. Existing test signing certificate retained.
- Normal release optimization enabled; instrumentation QA retention disabled. Build used offline dependencies with strict verification.

## Evidence and limits

768 ordinary JVM tests and 17 host integration tests passed with no failures, errors or skips. Android test Kotlin compilation passed. Lint passed with zero errors, 91 warnings and three hints. Compilation is not device instrumentation execution.

Package validation passed expected identity/certificate, no-network-permission, backup-exclusion, 16 KiB ZIP/ELF alignment, ABI/library-inventory, third-party-notice and exact packaged-source checks. All 16 native-library entries are byte-identical to previous APK `fe26cc65f418b328953ef32580b9ac97daeed4a612997bc54fd78b9a0dffbc9b`. Of 531 archive entries, only VCS metadata, baseline profile and DEX changed.

The first post-build adapter expected the label `sdkVersion`; current aapt2 emitted `minSdkVersion`. It stopped despite actual minimum SDK 29. A narrowly corrected strict parser passed read-only validation on the same pinned APK, with no rebuild. Original failure evidence was preserved; supervised process groups drained.

No new physical-device, installation/data-migration, production-signing or real-world scan-quality qualification is claimed. In-place upgrade is expected but untested. Do not uninstall or delete scans to work around problems; preserve/export important scans first.

## Delivery

On 2026-10-21 at 23:15:54 UTC, the ChatGPT send tool accepted three numbered ZIP parts and a standalone join/install README. Their concatenation and decompressed APK were verified exactly. The earlier single-ZIP Library upload succeeded but its attachment was explicitly rejected as oversized; it is not counted as delivery.

The accompanying JSON receipt records ZIP/part/README sizes and SHA-256 hashes. Delivery-tool acceptance does not prove user download or installation.
