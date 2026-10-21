# Photographic GLES sRGB filtering regression

This is a synthetic rendering correctness fix, not improved reconstructed geometry or complete scan/device qualification. Only GLES fallback changes; Filament and export bytes are unchanged.

## Finding and result

Ordinary RGBA8 upload plus linear filtering interpolated encoded sRGB: black/white midpoint128 rather than188. Immutable SRGB8_ALPHA8 storage decodes RGB before interpolation; output encodes once according to the actual framebuffer attachment. Alpha remains linear. Original and applied outputs, exact shader/source hashes, and baseline Git blob/commit are included. All16 desktop llvmpipe controls passed: original defect, corrected midpoint, solid gray, missing/double-encoding negatives, alpha masks and LINEAR/SRGB clear invariance. Original explicitcorrectness assertion exited1 as expected. No thresholds were tuned.

## Evidence categories

- `historical-executed/`: exact bytes of scripts actually run, preserved deliberately with their historical local machine paths. These paths are evidence only; do not run these scripts in a new checkout.
- `tools/check_gles.py`: separately adapted path-configurable replay, not claimed identical to the historical execution scripts. Same shaders/control values/algorithms; input path argument plumbing plus an explicit reject-optimized-Python guard. The guard-only successor was replayed separately; historical executed scripts remain unchanged. Its separate replay result is stored under results when executed.
- `sources/`: frozen original/applied renderer and extracted fragment GLSL. Source/math tests guard wiring; they do not replace real rendering.
- `report.json`: sanitized terminal summaries and raw RED log hashes. No raw process IDs included. Full Gradle/JVM/HOST/lint logs remain local and are independently reviewable. Host GPU results are desktop software GLES, not Android instrumentation.

## Reproduce on an existing Mesa EGL installation

Unoptimized Python3 (no `-O`/`PYTHONOPTIMIZE`) standard library and system Mesa EGL software driver suffice; the portable harness rejects optimized Python before loading EGL. No new packages are needed in the execution environment used for this evidence. The harness resolves GLES3 functions through eglGetProcAddress.

```sh
timeout 30s bash -c 'ulimit -v 2097152; python3 tools/check_gles.py --baseline-renderer sources/baseline-PhotographicRenderer.kt --renderer sources/applied-PhotographicRenderer.kt' > replay.json
```

The harness fixes software rendering,one LLVM thread,disabled shader cache,1x1 output and2x1 texture. Add `--require-production-correct` to assert the original baseline midpoint against188: it must fail with exit1 for this archived baseline. Ordinary controls intentionally validate the preserved defect and corrected result separately. To test the live implementation, point `--renderer` at its production Kotlin source; baseline must remain frozen.

The Android regression was compiled but not run. Actual GLUtils/Bitmap upload, Android lifecycle and hardware GPU qualification remain NOT_RUN. The new immutable texture reallocation follows existing context-loss resource paths; source review and desktop shader controls are not Android lifecycle proof.

## Primary sources

- glTF base-color filtering and alpha: https://github.com/KhronosGroup/glTF/blob/main/specification/2.0/Specification.adoc#materials
- AOSP Bitmap upload bridge: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/jni/android/opengl/util.cpp (default RGBA8888 mapping and direct subimage pixel upload). Patch uses immutable GLES storage then RGBA GLUtils subimage, not a historical internalformat overload assumption.
- Android straight-alpha decode: https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inPremultiplied
- Default GL_BACK query and color encoding: https://raw.githubusercontent.com/KhronosGroup/OpenGL-Refpages/main/es3.0/glGetFramebufferAttachmentParameteriv.xml
- EGL surface colorspace and GLES automatic sRGB writes: https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_gl_colorspace.txt

PhotographicGlb excludes COLOR_0 and baseColorFactor, so this change does not reinterpret vertex colors/factors. Broader PNG metadata/ICC handling is outside this narrow fix.
