# M19 — Revision-bound scan evidence and capture guidance

## What this measures

The Android reconstruction saves a versioned `scanDiagnostics` object directly
inside the existing revision-guarded sparse `*.json` result file. It contains:

- `registeredCameraCount / importedPhotoCount`: feature registration acceptance.
- Optional median parallax and normalized residual (these are not mm or
  reliable whole-object surface error measurements).
- Reciprocal verified depth sample ratio against all photometrically sampled
  reference pixels; this is a **sparse evidence fraction**, not the share of
  the object reconstructed.
- Observed mesh triangle and vertex counts, independently accepted by native
  visibility/depth filters.
- Photographic face fraction: coverage of reconstructed triangles by actual
  source-image pixels, not completeness of a 360-degree model.

There is **no single quality score**. The named technical statuses distinguish
`SPARSE_OR_DEPTH_ONLY`, `PARTIAL_OPEN_MESH`, and
`PHOTO_TEXTURED_OPEN_SURFACE`. Even the last one explicitly denotes **open
observed surfaces** and makes no manifold/print-ready guarantee.

## Recommendations

Heuristic, deterministic thresholds are defined and tested in
`ScanQualityEvidence.kt`:

- Too few registered photographs: add overlapping, sideways viewpoints.
- Less than 75% of imported photos registered: improve match overlap.
- Median parallax under 1.5 degrees: move sideways, don't rotate in place.
- Median normalized reprojection residual above .008: check lens/blur.
- Reciprocal depth evidence below 15% of sampled pixels: add matte-textured
  overlapping viewpoints.
- Below 100 accepted triangles or 30 vertices: do not claim a usable mesh.
- Under 75% photographic coverage of *existing* triangles: photograph more
  visible sides under steady lighting.

These cutoffs are warning heuristics, not certified scanner accuracy specs.
Malformed or impossible metric relationships are rejected rather than causing
a false-positive "good scan" badge.

## User flow and privacy

The new result screen displays a text-based evidence report and prioritized
recapture actions. `Export scan diagnostics (JSON)` uses the Android system
CreateDocument picker and the existing revision/identity-checked copy flow;
it contains point/camera metadata, but not original image bytes. No app internet
permission or remote analytics have been introduced.

## Tests and remaining work

Pure Kotlin JVM tests check status classification, threshold priority and
malformed values; Android instrumentation supplies representative result JSON
and verifies counts, missing-mesh behavior and invalid metrics. Native meshes,
UVs, camera registration and storage acceptance gates are unchanged; existing
C++/Python/Android CI tests are still required.

No physical Android camera scan, real-object ground-truth completeness,
thermal measurements, watertightness or final app release is implied.
