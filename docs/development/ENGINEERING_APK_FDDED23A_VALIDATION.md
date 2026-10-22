# Engineering candidate fdded23a

Includes the heartbeat crash fix and linear-light texture correction, with earlier topology-diagnostic fixes. This is not a production or device-qualified release.

## Immutable build identity
- Source: `fdded23aed856229c679ba4c968160545007c32a`; retain this historical identity even if repository history changes.
- APK: `realityforge-fdded23a-engineering.apk`, 106,220,465 bytes.
- SHA-256: `d28504761984c6a31e7fa0919651423ac57982d636f4428c9fe0298b0f2ab473`.
- Package `com.realityforge.scanner`, version `0.19.0-m19`, code 19, minimum API 29; existing test certificate retained.
- Optimized release build; instrumentation QA retention off; offline dependencies, strict verification; source clean before and after.

## Validation and limits
768 ordinary JVM tests, 18 host integration tests, 1,388 Python tests and six subtests passed; three optional Python checks skipped. Android test Kotlin compilation and lint passed. Sixteen Mesa software controls passed; these are not Android GPU validation.

APK identity/signature, no-network permissions, backup exclusions, 16 KiB ZIP/ELF alignment, notices and exact VCS revision passed. All 16 native library entries are byte-identical to prior APK `ee1736e82742246911add0ccddd426ab4de7449f2435322f3c6987714423b4c0`. Among 531 archive entries, only VCS metadata, baseline profile and DEX changed. Bounded supervised process groups drained.

Physical-device qualification, Android GPU controls, new Android runtime qualification and installation/upgrade/data migration were **not run**. No production-signing or real-world scan-quality claim is made. Do not uninstall or delete scans to work around a problem; preserve/export important scans first.

## Delivery
The ChatGPT send tool accepted three numbered ZIP parts and a join/install README at 2026-10-22 00:04:34 UTC. Concatenation exactly reproduced the ZIP; decompressed APK, README and receipt hashes were verified. Acceptance does not establish user download or installation. The accompanying JSON records all public artifact hashes and sizes.
