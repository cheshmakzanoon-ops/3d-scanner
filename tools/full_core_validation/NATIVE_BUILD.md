# Source-only additive JNI capability recipe

This documents the accepted production-source build, not an automatic dependency installer. Existing official Linux OpenCV4.12, JDK17 JNI headers, GCC C++20/binutils, zlib development files and a verified production scanner engine are prerequisites. No download, privilege, security or persistent-loader change is required. Original source commit for the successful capability was515f1435a497dd1734c51ef46f26e8f4ef136950.

Use an owner-reviewed fresh external output directory. Set absolute `REPO`, `JDK`, `ENGINE_DIR`, `CXX`, `OUT` paths; use the actual compiler executable, not an unreviewed shell command. Do not overwrite an earlier capability. Under an outer180s process watchdog, compile these unmodified repository translation units sequentially:

```
for unit in jni asset_exports_jni legacy_asset_export asset_model obj_export stl_export; do
  "$CXX" -std=c++20 -O0 -fPIC \
    -I"$REPO/reconstruction/include" -I"$JDK/include" -I"$JDK/include/linux" \
    -MD -MF "$OUT/$unit.d" -c "$REPO/reconstruction/src/$unit.cpp" \
    -o "$OUT/$unit.o" || exit 1
done
"$CXX" -shared "$OUT/jni.o" "$OUT/asset_exports_jni.o" \
  "$OUT/legacy_asset_export.o" "$OUT/asset_model.o" "$OUT/obj_export.o" "$OUT/stl_export.o" \
  -L"$ENGINE_DIR" -lrealityforge -lz -Wl,-z,defs \
  -Wl,-rpath,"$ENGINE_DIR" -o "$OUT/librealityforge_jni.so"
```

Record commands, compiler/linker/JDK/tool version and hashes, all six TUs, repository headers, all compiler `.d` consumed files (including system/zlib headers), resolved link dependencies, base engine hash and before/after source integrity. Capture readelf dynamic entries, nm defined exports and ldd-r relocation diagnostics of these trusted locally built artifacts. Require no unresolved symbols, all previous28 NativeCore JNI entries retained plus exactly `Java_com_realityforge_scanner_export_NativeAssetExports_writeObserved`, no strong rf symbol collision with base engine and no newly overlapping weak rf definitions. Confirm DT_NEEDED uses the separately verified `librealityforge.so` and RUNPATH exactly its parent; do not point LD_LIBRARY_PATH at the alias directory.

Historical accepted output SHA256519a3d15329520211d1d67756fe619308453e35445b349f2c162362b941af9ea; receipt SHA2567382f0436faec9838c15bd6b5db4f51738ee47efd77c4e9dd13f821705795953. Base engine SHA2564384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b; official OpenCV JNI SHA256e573a54e917ca142706d37537058009000c0df9157bc46cb66fbfec5a522d2d1. Build/link success is not runtime publication/export success.

The historical core-only shim compiles `jni.cpp` alone against that engine, SHA256427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce. Keep it separate from the additive export capability; never overwrite prior proofs. Native build receipts establish explicit source/capability relationships beyond hashes alone. If toolchain/path changes produce another hash, stop for a new reviewed capability manifest and loader/runtime qualification; the harness must not auto-trust it.
