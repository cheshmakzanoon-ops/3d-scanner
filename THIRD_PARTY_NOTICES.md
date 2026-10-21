# Resolved dependency notices and provenance

The release graph is recorded in `tools/dependencies/evidence/inventory.json` and
`sbom.cdx.json`: 123 runtime modules (including metadata-only platforms), 97 compile
modules and 38 KSP processor modules, 155 distinct coordinates. Runtime attribution
is packaged under `app/src/main/assets/third-party-notices/`; NOTICE_INDEX.json binds
all 92 full license/notice files and the exact runtime coordinate set. Build processors
and host benchmark tools are identified separately; they are not app runtime libraries.

The native app core is first-party. Shipped native dependencies are OpenCV4.12.0,
its libc++ redistributable, AndroidX Camera1.4.2 image/surface utilities and AndroidX
Graphics Path1.0.1. `inventory.json` binds all ten dependency native libraries across
arm64-v8a/x86_64 by artifact hash and GNU build identity, allowing stripped package
binaries to reconcile with resolved source artifacts. The app core's source provenance
is the Agent094 exact Git manifest/archive; no ownership license is inferred.

OpenCV's official4.12.0 Android SDK archive was obtained from its tagged GitHub release.
Both libopencv_java4.so binaries are byte-identical to the resolved MavenAAR. The
SDK's complete supplied `sdk/etc/licenses` directory plus top-level LICENSE are
included. This preserves notices for its bundled third-party components rather than
assuming Apache2.0 alone covers every embedded component. Exact SDK/archive/AAR/file
hashes are in `opencv-sdk-provenance.json`. AndroidNDK28.2 full NOTICE/NOTICE.toolchain
texts are included for native redistribution. Additional full Apache2.0, Kotlin2.1.21,
coroutines1.10.2, ANTLR and libyuv notices supplement upstream resource notices
preserved even where generic META-INF licenses are excluded by Android packaging.

Resolved license declarations come from exact artifact POMs, including parent-POM
inheritance, with each evidence POM SHA256. Apache2.0 dominates AndroidX/Kotlin/OpenCV;
Camera's libyuv and Room's embeddedANTLR declare BSD terms. Host-only Python packages
have a separate inventory in `host-tools.json` and CPython3.12/Linuxx86_64 hashed wheel
lock in `tools/requirements.lock`. Their licenses do not imply they ship in Android.

Reproduce graph/hashes and notices with `tools/dependencies/README.md`. Final APK/AAB
attribution audit rejects missing/altered notices, graph drift, unexpected native names
and changed native GNU build IDs. APK signature/alignment/privacy gates remain Agent091.

**BLOCKED_EXTERNAL:** the owner has not selected a first-party distribution license.
This inventory preserves third-party evidence; it grants no first-party distribution
permission and makes no legal approval claim. Ceres, Eigen, Filament, xatlas,
meshoptimizer and VulkanMVS are not approved or shipped by this baseline. Each proposed
addition needs exact version/origin/hash/license review before shared dependency wiring.
The candidate package gate must rerun after any integrated dependency change.

The debug APK resolves129 runtime modules, with a separate actual debug inventory
and SBOM under tools/dependencies/evidence/debug/. Its app/src/debug notice source
set supplies all95 full texts and a129-coordinate index, replacing common asset paths
through normal AGP merging. The six additional coordinates are Compose material,
ui-tooling and ui-tooling-data Android artifacts and their metadata, all version1.8.2
with actual Apache2.0 POM declarations and preserved artifact notices. These are
actual debug runtime dependencies; they are absent from the123-module release.

## Integrated build and test tooling
The committed Gradle 8.13 wrapper retains its Apache 2.0 license in gradle/wrapper/LICENSE. JVM-only org.json:json:20250517 is declared Public Domain by its upstream POM and is not an Android runtime dependency. This combined candidate requires refreshed dependency and package verification; historical inventory is not proof of the combined build.

## Filament 1.56.0 renderer integration candidate

The candidate now declares `com.google.android.filament:filament-android:1.56.0`
and `com.google.android.filament:gltfio-android:1.56.0`. This supersedes the earlier
baseline statement that Filament is not shipped only for the new integration
candidate; that baseline inventory and its counts remain historical. Filament's
source build also bundles meshoptimizer internally; it is not a separately
approved app dependency or an assertion that a standalone artifact was added.

Exact upstream v1.56.0 full license texts and source copyright headers are
packaged under `app/src/main/assets/third-party-notices/filament-1.56.0/`.
Its README describes the source-derived bundled-component scope and conditional
Vulkan notices. SOURCES.json records source URLs and verified text hashes.
Official Central AARs with the hashes recorded in that README were inspected,
including nested classes.jar: neither AAR supplies standalone license/notice
files, so Apache-only Maven declarations are not treated as complete attribution.
The supplement includes Draco, Basis Universal/zstd, cgltf, stb, robin-map,
meshoptimizer, MikkTSpace and AMD FSR notices, plus conservative conditional
Vulkan dependency notices.

The runtime inventory, SBOM, NOTICE_INDEX, native library identity records and
final APK/AAB package gates still require regeneration for the combined candidate;
this supplement does not claim those historical records cover Filament, establish
exact published-binary composition, or grant legal/first-party distribution approval.

### Candidate verification update (2026-10-21)

The integration gates immediately above were subsequently run for the debug
candidate. Strict Gradle exports resolved 125 release / 131 debug runtime modules;
the only additions to the prior inventories were the two Filament 1.56.0 modules,
with all prior artifact identities unchanged. Inventory/SBOM/native identities and
both notice indices were regenerated. Release has 111 indexed files; debug has
114, including all 19 files in the Filament supplement in each variant.

The rebuilt debug APK SHA-256
`619b6167e944869744c71c8d12b5317536aea6f2a6047961d04d01a9a1f100b9`
passed the dependency package audit: all indexed text bytes and the index matched,
all 131 runtime coordinates matched, and all 14 dependency native entries across
arm64-v8a/x86_64 matched their resolved GNU build identities (the two first-party
native entries are checked separately through source provenance). The 26 dependency
regression tests passed. This evidence applies to that exact debug APK, not a later
rebuild, release APK/AAB, physical-device behavior, or legal distribution approval.
