# Scoped owner004 build integration request

This is an external campaign handoff, not a modification of owner004 files.
The combined source currently has ZLIB registration but its `realityforge` target
does not compile the delivered native modules. Add these seven exact sources to
the existing target, preserving the existing JNI source and platform ZLIB link:

```
reconstruction/src/asset_model.cpp
reconstruction/src/glb_export.cpp
reconstruction/src/obj_export.cpp
reconstruction/src/stl_export.cpp
reconstruction/src/ply_export.cpp
reconstruction/src/mesh_lod.cpp
reconstruction/src/asset_edit.cpp
```

Register owner069's pinned dependencies in app/build.gradle.kts:

```kotlin
implementation("com.google.android.filament:filament-android:1.56.0")
implementation("com.google.android.filament:gltfio-android:1.56.0")
```

`final-validation.py` uses an external CMake wrapper and Gradle finalizeDsl hook
to verify the same registrations in a fresh immutable temporary worktree. Its
receipts explicitly identify the adapter; an ordinary source-only checkout still
requires owner004 to commit the registrations. Expected validation includes full
debug/test APK assembly, all Gradle JVM tests, lint, 16KiB ELF/ZIP checks, seven
native source compile-command entries for both ABIs, and exported symbol checks
against the actual packaged librealityforge.so bytes.

Native inclusion is separate from owner003 JNI and owner071/067 route invocation.
The verification adapter does not make those absent runtime bindings complete.
