# M15 — Adaptive Android multi-view depth input

## Shipped behavior in this source increment

The depth adapter no longer truncates the registered list to its first three
cameras. It sends up to eight to M14, preserving both anchor cameras and choosing
a deterministic spread through the remainder when there are nine to twelve.
Source scheduling remains geometric/photometric; the selection itself is not a
measurement of object completeness. Sparse registration is still capped at twelve.

With a managed-heap limit of at least 256 MiB the depth decode edge is at most
384 pixels. A compact tier uses 256 pixels. Both keep all selected views and
choose the smallest stride >=3 for which the **exact native sampled-grid count**
is <=100,000. Eight 384-square images therefore use stride 4; eight 384x256 images
use stride 3. There is no full image/depth-volume allocation. Native per-reference
luminance still uses at most the reference plus three selected source images.
These are explicit array/sampling bounds, not measured process RSS or an assurance
that every device will finish within a given time or temperature.

Only one decoded original bitmap and any temporary same-aspect rescale remain
live at a time. Pixel buffers are filled incrementally and bitmaps are recycled
in finally blocks even when preprocessing fails. Rectification checks cancellation
at row boundaries and no longer creates a four-element array for every pixel.
Native reconstruction remains monolithic and cannot yet be interrupted mid-call.

Texture sources are capped at 640px for five to eight standard-tier views,
768px for smaller standard jobs, or 512px in the compact tier. The assembled
atlas is checked against the existing 4.5-million-pixel photographic viewer limit
**before** its input allocation. Standard eight-square-view atlases are 1938x1938,
not eight separately unbounded full-resolution images. Originals are not changed.

Sparse depth priors now require positive camera-Z **and projection inside the
rectified image**. Points behind the camera or outside its field no longer widen
the common inverse-depth interval. This is still one shared interval, not an
adaptive per-pixel or per-view depth hierarchy.

The engine/cache key includes M15 and the compact/standard tier. Prior project
results remain readable but new results are not confused with M10's three-view
settings. Output diagnostics identify selected cameras/photos, skipped registered
cameras, actual dimensions/stride and planned array/sample bounds. The UI displays
actual depth dimensions instead of suggesting unmeasured high-resolution scans.

## Verification gates

- Eight JVM policy cases: native-loop sample counts, square/landscape limits,
  compact tier, eight-view selection, atlas overflow and visibility-based priors.
- A new Android instrumentation case imports eight explicitly synthetic photo
  files and runs the real DensePreview adapter through textured GLB readback.
  It checks the last camera contributes, that the workload is bounded, and that
  photographic exports remain readable. Known poses are supplied; this is not
  a validation of eight-camera SfM registration or real phone capture.
- Existing end-to-end three-photo app session, re-open/export, GLES pixel-readback,
  permissions, native tests and real Middlebury stereo benchmarks remain enabled.
- Local CTest/pytest can validate native/host contracts. Kotlin compilation and
  emulator assertions must be checked on the commit-specific GitHub CI run.

## Remaining product gaps

This is still an engineering build, not a complete or perfect scanner. No physical
Android handset, thermal/battery profiling, general 360-degree object accuracy,
watertight/manifold topology, compact UV charts, large-scene checkpoints or
full-resolution dense reconstruction is claimed. Complete-object camera
registration and measured real-device performance are the next gates.
