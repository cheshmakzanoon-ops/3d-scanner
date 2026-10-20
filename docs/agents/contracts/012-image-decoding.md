# Agent 012 image decoding and ownership contract

Owner: Agent 012 (`agents/012-image-decoding-lifetimes`)

## Public seam

New consumers use `com.realityforge.scanner.imaging.DecodeRequest` with either:

- `ImageLoader.decode(request)` from a coroutine; decoding runs on `Dispatchers.IO`.
- `ImageLoader.decodeBlocking(request)` only from an already-owned background worker.

Both return `OwnedImage`. The owner must be closed. `withBitmap` borrows the bitmap only for the
owner lifetime. `transferBitmap` is the explicit compatibility escape hatch and transfers recycling
duty to the caller. `ImageLoader.upright(file, maxEdge)` remains source-compatible and returns a
caller-owned bitmap exactly as before.

## Decode invariants

- The encoded source file is opened read-only and never rewritten.
- Bounds are inspected before pixel decode. Default limits are 150 MiB encoded bytes, 32,768 pixels
  per source dimension, 200 megapixels, 1,600 output edge and 64 MiB estimated decode peak.
- Sampling is power-of-two and selected before decode. The planner reserves a decoded ARGB bitmap,
  one final rendered ARGB bitmap and fixed decoder overhead; exact Android allocation sizes are
  checked before the final render. Final dimensions remain within `maxEdge` and preserve the
  existing three-pixel minimum on either axis, including highly sampled panoramic inputs.
- All eight TIFF/EXIF orientations are represented by `ExifOrientation`. Pixel-center coordinate
  goldens and the actual Canvas affine matrices are independently checked for all eight values.
  Missing or malformed orientation metadata is explicit evidence and falls back to normal
  orientation rather than leaking a decoded bitmap or changing the original.
- Orientation, final downscale and color conversion are combined into one Canvas render, avoiding
  an orientation bitmap followed by a second scaled bitmap. A coroutine cancellation observed after
  the blocking platform decode closes the undelivered owned bitmap before propagating cancellation.
- Supported analysis inputs are JPEG, PNG, WebP and platform-decodable HEIF/HEIC. Other MIME types
  are rejected even when an extension resembles a supported photograph.
- Final images are sRGB `ARGB_8888`. Decoded alpha is preserved. Quality analysis composites alpha
  over black and uses the documented integer Rec.601 luma approximation.
- Quality conversion holds one byte per pixel plus one integer scanline. It no longer overlaps a
  full-frame `IntArray` with a full-frame `ByteArray`.

## Typed failures

`ImageDecodeException.failure` is one of:

`INVALID_REQUEST`, `FILE_NOT_FOUND`, `ENCODED_SIZE_LIMIT`, `UNSUPPORTED_FORMAT`, `CORRUPT_IMAGE`,
`DIMENSION_LIMIT`, `SOURCE_PIXEL_LIMIT`, `RESOURCE_LIMIT`, `DECODE_FAILED`, `TRANSFORM_FAILED`, or
`CLOSED_IMAGE`.

The exception remains an `IllegalArgumentException` so existing callers continue to fail through
their established validation path. Agent 010 owns any shared cross-stage diagnostic-code mapping.

## Consumer handoff

Agents 011, 013, 016, 027 and preview/pipeline owners can adopt `DecodeRequest` and `OwnedImage`
without depending on capture metadata DTOs. The file plus immutable limits are sufficient. Focal
metadata remains a separate optional adapter (`ImageLoader.focal35`) until Agent 013 publishes the
capture-metadata identity contract.

A physical ARM64 JPEG/PNG/WebP/HEIF/HEIC matrix remains a required device gate. Emulator decoding
and generated fixtures do not qualify physical vendor codec behavior or process-memory budgets.

The receiving032 resource review fixed two final ownership windows. decodeBlocking constructs
OwnedImage before clearing tracked Bitmap references, so owner/lock construction failure still
reaches bitmap cleanup. suspend decode tracks its owner outside withContext: the IO block returns
Unit, successful dispatcher return transfers ownership, and prompt cancellation before delivery
closes the undelivered owner. Blocking ImageLoader.upright remains source compatible.

Canonical decoder policy is AndroidImageDecoder.POLICY_IDENTITY:
`rf-owned-image-v3:srgb-argb8888:exif8-centres:ceil-power2:defaultEdge1600:defaultPeak67108864`.
Consumers include this plus their actual decoder/extractor options in derived-cache and stage
identity. Default limits are1600 pixels/67108864 bytes; requested options remain explicit.
The ceil-based sampling and sRGB policy can change decoded output from the old embedded decoder,
so previous feature/depth descendants must miss/recompute.031 Canvas edge-to-pixel-center
conventions remain identical across all eight EXIF transforms.

ImageOwnershipHandoffInstrumentedTest deterministically queues caller resumption after actual
Android Bitmap ownership is produced on IO. It checks cancellation recycles the undelivered
bitmap and successful delivery keeps it live until caller close. Run that class alongside
ImageDecodingInstrumentedTest on the frozen integrated APK; source compilation alone is not
runtime cancellation evidence. Actual native allocation failure inside owner construction is
covered by the retained cleanup ordering, not falsely claimed as an injected OOM measurement.

AndroidX ExifInterface1.4.1 inserts an undefined orientation0 for files with no orientation tag.
Its compatibility attribute has byte offset-1, so getAttribute/hasAttribute alone cannot
establish physical presence. A fresh reader now treats only undefined0 without a physical
attribute range as ABSENT; a physically stored0 (or another invalid value) remains MALFORMED.
Valid derived HEIF rotations1..8 remain PRESENT even when their range is unavailable. The
instrumentation oracle independently reads the controlled JPEG APP1/TIFF bytes to distinguish
absent, stored0 and all eight stored valid values, without using AndroidX's attribute map.
This corrects warning/evidence classification only: fallback orientation, decoded pixels,
allocation limits and ownership remain identical, so the decoder pixel-policy identity is
unchanged. Actual platform execution of the corrected fixture must be recorded separately.

User-requested stop snapshot: the conservative physical-presence fallback is saved as WIP.
It checks bounded JPEG/PNG/WebP EXIF containers and primary TIFF directories before claiming
ABSENT. Present tags, malformed/truncated metadata, duplicates, caps and unreadable evidence
remain untrusted. Unsupported HEIF absence proof maps to the existing MALFORMED evidence enum;
that conservative mapping does not assert that the image bytes are corrupt. Valid1..8 derived
rotations are handled first. Pixel transforms, limits, ownership and policy identity are unchanged.
The actual Android wrong-type ASCII-tag injection fixture was not implemented before the stop;
no corrected-device success or final immutable review is claimed. Prior focused source/JVM
results are preserved separately. Standalone032/035 have not received this unfinished followup.
