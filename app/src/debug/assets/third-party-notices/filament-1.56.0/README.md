# Filament Android 1.56.0 attribution supplement

Applies to the integration candidate using:
- `com.google.android.filament:filament-android:1.56.0`
- `com.google.android.filament:gltfio-android:1.56.0`

Upstream: https://github.com/google/filament/tree/v1.56.0
GitHub recursive tree SHA: `003e5005713cba55cbd3f3a423c472000b96b868`.
`SOURCES.json` records the exact source URLs and locally verified SHA-256 values
of the retained texts. Full license files are unmodified; three explicitly marked
copyright/license header extracts retain the source lines verbatim. No standalone
NOTICE file was present in the upstream recursive tag tree.

## Source-based scope

The tag's `android/gltfio-android/CMakeLists.txt` imports/links Draco,
meshoptimizer, stb, Basis Universal and zstd, and includes cgltf and robin-map.
`android/filament-android/CMakeLists.txt` links geometry;
`libs/geometry/CMakeLists.txt` links meshoptimizer and MikkTSpace. MikkTSpace's
complete license header is retained because it has no separate LICENSE file.
`filament/CMakeLists.txt` includes the AMD FidelityFX Super Resolution shader
sources; its MIT license is retained. Basis Universal's upstream REUSE metadata
and copyright header supplement its Apache license and zstd's BSD license.

The bundled terms include Apache 2.0 (Filament, Draco, Basis Universal), MIT
(cgltf, meshoptimizer, robin-map, AMD FSR), BSD (zstd), MikkTSpace's zlib-style
terms, and stb's offered MIT/public-domain alternatives. The authoritative terms,
copyright holders, conditions and disclaimers are in the accompanying texts,
not this summary. Preserve the full texts with redistributed binaries; do not
remove copyright/permission/disclaimer notices or claim upstream authorship.
Apache terms also address modified files and any supplied upstream NOTICE.

Conditional Vulkan support has additional smol-v, Vulkan Memory Allocator,
SPIR-V Headers and Khronos Vulkan header attribution retained conservatively.
Their inclusion here does not assert Vulkan is enabled in the published AARs.
No standalone meshoptimizer Maven artifact or direct app API integration is
implied by its presence as an upstream static dependency.

## AAR inspection (2026-10-21)

The official Maven Central AAR files inspected have SHA-256:
- filament-android: `b03d35dd50698d0c61e28eec24734b7b257a0f13017817375598c0d3af49da39`
- gltfio-android: `25370a7b6a5af3be66367b6d609deed18b37333e1963b7ee6856f50e0dab544e`

Origin pattern:
`https://repo.maven.apache.org/maven2/com/google/android/filament/<artifact>/1.56.0/<artifact>-1.56.0.aar`

Both AAR ZIP directory listings and their nested `classes.jar` listings were
inspected. Neither contains a file named for LICENSE, NOTICE or COPYRIGHT.
Each provides its JNI library (`libfilament-jni.so` or `libgltfio-jni.so`) for
arm64-v8a, armeabi-v7a, x86 and x86_64. This is archive inspection, not proof of
which ABI files the final application packages or of every statically linked
component. Source-based attribution is therefore supplied separately here.

## Remaining integration gates

This supplement is not a refreshed runtime inventory, SBOM, NOTICE_INDEX, native
binary identity manifest, or package audit. Those must be regenerated against the
actual resolved candidate and its final APK/AAB, including checking these assets
survive debug/main asset merging. Historical baseline counts/hashes do not cover
these additions. Exact compiler/build options and complete source-to-published-
binary composition were not established by this notice audit. Recheck attribution
if additional artifacts (for example filamat) or custom builds are introduced.
This is technical attribution evidence, not legal approval or a first-party
license grant.
