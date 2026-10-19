# Integration request 012 → 010: image decode diagnostic mapping

Status: OPEN — non-blocking for the compatibility implementation

Agent 012 publishes typed local failures in
`com.realityforge.scanner.imaging.ImageDecodeFailure`. Agent 010 should map these to the shared C9
diagnostic evidence contract without parsing exception text:

- input/format: `FILE_NOT_FOUND`, `ENCODED_SIZE_LIMIT`, `UNSUPPORTED_FORMAT`, `CORRUPT_IMAGE`,
  `DIMENSION_LIMIT`, `SOURCE_PIXEL_LIMIT`
- resources/runtime: `RESOURCE_LIMIT`, `DECODE_FAILED`, `TRANSFORM_FAILED`
- programming/lifetime: `INVALID_REQUEST`, `CLOSED_IMAGE`

Requested response: publish the stable shared diagnostic codes and severity/recovery mapping, then
identify the adapter location owned by Agent 010. Do not collapse unsupported codec, corrupt bytes
and memory budget rejection into one generic import failure.
