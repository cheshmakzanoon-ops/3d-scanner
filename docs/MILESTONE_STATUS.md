# Current status — M12

| Area | Actual state |
|---|---|
| App/capture | Kotlin/Compose/CameraX, local imports, calibration, private project storage |
| Sparse geometry | Shared C++ SfM/PnP, multi-view tracks and joint Schur-complement bundle adjustment; fixed intrinsics |
| Depth/surface | Bounded CPU stereo, reciprocal checks and open indexed triangle patches; not complete manifold reconstruction |
| Photographic UV | Full XYZ cross product and glTF top-left origin repaired; all 3,530 existing triangles textured on one real stereo benchmark |
| Appearance | Bounded, measured exposure gains and deterministic camera-label smoothing; not seamless blending |
| Native validation | Seven CTest targets and 105 Python checks passed locally; M10 photometric GCC/Clang/sanitizer evidence retained |
| Android validation | M11 full CI and 20 emulator tests passed; M12 new session tests require CI; no physical camera proof |
| Preview | Bounded local photographic GLES3 viewer plus vertex-color diagnostics; new shader/file/lifecycle tests |
| Resource limits | At most 12 sparse photos, three Android depth views at 256px edge, texture sources up to 768px |
| Release status | Engineering prototype only; not consumer/store ready |

The new fixes improve measured material projection without altering mesh geometry
or relaxing visibility gates. 100% benchmark triangle coverage is not whole-object
completeness. See M10_VALIDATION.md for actual numbers and test boundaries.

Historical implementation/validation records are retained in M02–M09 documents.
The full product contract remains PROJECT_PLAN.md; phases requiring physical
handsets, complete-object ground truth and sustained resource measurements remain
open. No synthetic or two-camera stereo test closes those gates.

See M11_VIEWER.md for supported GLB subset, memory budgets and exact tests. No reconstruction quality ceiling or physical-device gate is changed by the viewer.

M12 adds bounded revision-checked reopening, operation reservation around exports/deletion,
cancellable copying and capture-revision checks. The full synthetic photograph-to-model
session is exercised with the actual Android ViewModel/pipeline, not only native fixtures.
See M12_SESSION_RELIABILITY.md; unchanged geometry limits and physical gates remain open.


## M14 / M15 increments

- M14 native/JNI: 2–8 calibrated views, up to three selected matching sources per
  reference, explicit 100k sample budget, active luminance caching, eight-camera
  exposure solve and bounded 3x3 photographic atlas. Known-camera synthetic plane
  coverage increased 1.71x against the same fixed eligible surface grid; the
  existing real stereo output is unchanged. Not a full object/phone benchmark.
- M15 Android: activates up to eight views, adaptive 384/256 depth edge and stride,
  compact/standard texture limits, one-image-at-a-time decode, cancellation checks
  during rectification and visible sparse depth priors. New settings are versioned.
- Kotlin and device assertions are commit-specific CI gates. Physical handset,
  large-scene resumption, arbitrary 360-degree registration and final-quality
  topology/textures remain outstanding. Neither milestone establishes perfection.
