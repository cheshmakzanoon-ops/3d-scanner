# Conservative EXIF focal-plane prior

When the direct `FocalLengthIn35mmFilm` tag is absent, imported images can supply
an **uncalibrated, derived** diagonal-equivalent focal prior from `FocalLength`,
`FocalPlaneXResolution`, `FocalPlaneYResolution`, `FocalPlaneResolutionUnit` and
`PixelXDimension`/`PixelYDimension`. No camera-model database or network is used.

Source: [CIPA Exif 2.32, DC-X008-2019, page60](https://www.cipa.jp/std/documents/e/DC-X008-Translation-2019-E.pdf).
Focal-plane resolutions describe the processed main image, not necessarily the
physical sensor. `sensorSizeMm` remains missing. EXIF values are metadata, not a
lens calibration; principal point and distortion remain unmeasured.

The helper derives recorded-image extents `W * unitMm / XResolution` and
`H * unitMm / YResolution`, then `focalMm * hypot(36,24) / hypot(extentX,extentY)`.
This matches the engine's existing **diagonal** convention, not a horizontal-only
conversion. Only explicit inch (`2`,25.4mm) and centimeter (`3`,10mm) units are
accepted; dimensionless, absent and unsupported units are declined.

The scalar engine fallback assumes `fx == fy`. Therefore unequal focal-plane
resolutions are declined beyond a relative `1e-6` numeric-rounding tolerance.
No averaging of meaningfully anisotropic axes is performed. All eight valid EXIF
orientations leave the scalar diagonal unchanged; inputs must match the actual
encoded image axes before rotation. EXIF/TIFF contradictory dimensions, stale
sizes, invalid values and declared digital crop also decline fallback. DigitalZoomRatio0 means no zoom
(CIPA page63),1 means unit zoom; both are accepted without rescaling.
A present direct35mm tag takes precedence, including retaining unknown status
for a malformed tag. Existing explicit measured calibration precedence is unchanged.

The evidence-bearing adapter marks accepted fallback `DERIVED` with EXIF and
encoded-image metadata origins. Legacy `ImageLoader.focal35` consumes the same
value as an estimated prior, never a measured calibration. Existing stored imports
are not migrated or silently reinterpreted; newly imported valid metadata changes
the photo metadata identity through the existing import pipeline.

Exact anisotropic intrinsic propagation through sparse, dense, caches and stored
metadata remains future work rather than a hidden approximation.

Validation: pure Kotlin regression tests cover units, finite/bounded values,
axis mismatch, dimensions, orientations and provenance. Android instrumentation
adds actual JPEG/EXIF parsing, byte preservation, direct-tag priority and malformed
metadata cases. Host unit results do not substitute for running those device tests.
