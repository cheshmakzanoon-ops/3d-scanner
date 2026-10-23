# NDK 28.2 configuration prerequisite: source/metadata evidence only

This compact package supports the [reviewed prerequisite design](../../EIGHT_VIEW_HOST_NDK_PREREQUISITE.md). It is not an installed SDK or a successful pipeline receipt.

- `configuration-source-receipt.json`: official AGP 8.11.1 sources JAR and selected entries, including configuration-time NDK resolution and deferred CMake location lookup.
- `license-source-receipt.json`: official repository 31.11.1 sources JAR and exact license normalization/acceptance algorithm entries; license ID/hash only, no unnecessary full terms.
- `ndk-package-metadata.json`: bounded official repository XML identity and selected exact package metadata. CMake 3.22.1 was inspected historically but is **not selected for installation**.
- `ndk-zip-metadata.json`: exact official ZIP tail/central-directory HTTP range identities and declared entry/expansion counts. **Metadata-only inspection does not verify the complete archive**; full archive bytes/SHA-1 and measured SHA-256 are mandatory in an eventual authorized runner.

Primary URLs are recorded in each receipt. AGP source JAR: 3,810,095 bytes, SHA-256 `65bc5d04cc52655664894e196f274da781091602a219126b67fd13bd2621ddd6`. Repository source JAR: 204,899 bytes, SHA-256 `16ac8d8cc722f5dad2cd241e18ac50b9c8b0f961fb82a2478a6e850c201e7f22`. Official SDK XML: 423,137 bytes, SHA-256 `f2bc624c8e1dc14c320fdf17e49a5501c35934948e46a444df8b36a138a03e1e`.

These metadata files were obtained without downloading the full 722,261,334-byte NDK, extracting a package or executing Gradle/native tools. No new licenses, security settings or runtime assertions were changed. The c85 first-failure package remains unchanged; these later source findings do not rewrite it.

`validation.json` binds the four candidate implementation/test files and the actual **222 passing host guards (12.52 s)**. The full guard log remains external; its SHA-256 is retained. No native/SDK archive or build products are included.
