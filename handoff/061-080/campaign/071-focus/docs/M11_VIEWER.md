# M11 — bounded offline photographic model inspection

M10 was already pushed at fa64882eccc8d994ed293ce06491b71536dd53bc when work
resumed; its complete native/Android/emulator run 38192237881 succeeded. M11
closes the next product gap: inspecting the actual photograph-textured GLB
**inside the Android app**, not only in an external desktop viewer.

## Implementation

- A new `Inspect photo-textured model` control opens a full-screen local viewer.
  The original vertex-color diagnostic, sparse/depth views, and exports remain.
- `PhotographicGlb` validates the specific single-mesh, indexed, identity-node,
  embedded-PNG GLB subset emitted by RealityForge. It is deliberately NOT a
  general glTF importer. Unsupported transforms/extensions fail explicitly.
- Header/chunk lengths, buffer/accessor offsets and strides, integer types,
  finite positions/normals/UVs, triangle indices, material alpha mask, and PNG
  chunks/CRC/dimensions are checked before use. URI resources are never opened.
  JSON nesting is bounded before parsing. Growing/truncated files are rejected.
- Resource caps: 32 MiB GLB, 256 KiB JSON, 240,000 vertices, 240,000 indices,
  20 MiB PNG, 4.5 million decoded image pixels, 4096-pixel texture edge. GPU's
  reported maximum texture size is checked before bitmap decode/upload.
- An OpenGL ES 3 on-demand renderer draws all validated indexed triangles,
  with depth testing and the original embedded texture/UVs. It is explicitly
  **unlit photographic inspection**, not a physically measured PBR material.
  glTF top-left UV origin is preserved, alpha-masked holes remain empty, and
  there is no extra vertical flip. Original files/buffers are not modified.
- Orbit/pinch/reset controls operate on a bounded camera. The display centers
  and fits the bounding sphere without changing source geometry or exports.
  No continuous animation/render loop runs when the view is idle.
- Activity pause/resume is forwarded to GLSurfaceView. EGL recreation rebuilds
  resources from retained validated bytes. Closing removes the lifecycle
  observer, stops the GL thread and drops its context; explicit deletion is
  idempotent when a live context is available. Failed loading/drawing displays
  an error while leaving other representations intact.

## Validation contract

`PhotographicViewerTest` runs on a real Android emulator, not a mocked Canvas:

1. Parse known geometry/UV/index data without mutating original bytes.
2. Reject malformed headers, chunks, unsafe offsets, NaN, invalid indices,
   external URIs, unsupported transforms and oversized texture dimensions.
3. Read four analytically known colored quadrants back from an EGL pbuffer
   rendered by the same production shaders; explicitly catch V inversion.
4. Verify transparent regions, reset, repeated disposal and EGL context loss.
5. Open the real Compose dialog in MainActivity, wait for an actual GPU frame,
   pause/resume the Activity and require a subsequent frame with no GL error.
6. Existing native JNI reconstruction now opens its actual generated GLB through
   this loader and checks atlas/triangle counts against native diagnostics.

The four-color fixture is **synthetic analytical rendering evidence**, not a
claim about real-object reconstruction. Kotlin/NDK compilation, lint and these
instrumentation outcomes must be taken from this commit's Actions results.

## Architecture decision and limits

The approved production studio direction remains Filament. This incremental
unlit GLES3 viewer uses Android's existing APIs and no extra native dependency;
it establishes independent file/UV/lifecycle tests before a larger studio.
It does not claim to implement Filament PBR, crop/scale tools or arbitrary glTF.
Existing M10 reconstruction fingerprints remain valid: M11 changes viewing,
not the reconstruction algorithm. The 12-photo sparse / 3-view low-resolution
Android depth limits have NOT increased.

Primary API/specification references:
- https://developer.android.com/reference/android/opengl/GLSurfaceView
- https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html

Physical ARM64 camera captures, complete-object ground truth, sustained thermal
and RAM measurements, stronger surface topology, dense reconstruction scaling,
seamless atlas baking and production release gates are still outstanding.
