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
  checked before the final render.
- All eight TIFF/EXIF orientations are represented by `ExifOrientation`. Missing or malformed
  orientation metadata is explicit evidence and falls back to normal orientation rather than
  leaking a decoded bitmap or changing the original.
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
