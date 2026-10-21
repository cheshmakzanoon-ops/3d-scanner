#!/usr/bin/env python3
"""Validate an actual emulator PNG rather than accepting redirected adb error text."""
from __future__ import annotations
import argparse
from pathlib import Path
import struct
import zlib


def validate(path: Path) -> tuple[int, int]:
    if not path.is_file() or not 57 <= path.stat().st_size <= 20_000_000:
        raise ValueError("Screenshot missing or outside the image size budget")
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Screenshot is not PNG data (possibly an adb error)")
    cursor = 8
    width = height = 0
    pixels = False
    ended = False
    chunks = 0
    compressed = bytearray()
    channels = 0
    while cursor < len(data):
        if cursor + 12 > len(data) or chunks > 10000:
            raise ValueError("Invalid PNG structure")
        size = struct.unpack_from(">I", data, cursor)[0]
        if size > len(data) - cursor - 12:
            raise ValueError("Truncated PNG chunk")
        kind = data[cursor+4:cursor+8]
        payload = data[cursor+8:cursor+8+size]
        crc = struct.unpack_from(">I", data, cursor+8+size)[0]
        if zlib.crc32(kind + payload) & 0xffffffff != crc:
            raise ValueError("Corrupt PNG checksum")
        if chunks == 0:
            if kind != b"IHDR" or size != 13:
                raise ValueError("Invalid PNG header")
            width, height = struct.unpack_from(">II", payload)
            depth, color, compression, filtering, interlace = payload[8:]
            if depth != 8 or color not in (2, 6) or (compression, filtering, interlace) != (0, 0, 0):
                raise ValueError("Unsupported screenshot pixel encoding")
            channels = 3 if color == 2 else 4
            if not (64 <= width <= 8192 and 64 <= height <= 8192 and width*height <= 16_000_000):
                raise ValueError("Invalid screenshot dimensions")
        elif kind == b"IDAT":
            pixels = pixels or size > 0
            compressed.extend(payload)
        elif kind == b"IEND":
            if size or not pixels or cursor + 12 != len(data):
                raise ValueError("Invalid PNG end")
            ended = True
        elif kind == b"IHDR":
            raise ValueError("Repeated PNG header")
        cursor += size + 12
        chunks += 1
    if not ended:
        raise ValueError("Screenshot PNG is incomplete")
    expected = height * (width * channels + 1)
    decoder = zlib.decompressobj()
    try:
        raw = decoder.decompress(bytes(compressed), expected + 1)
    except zlib.error as error:
        raise ValueError("Invalid PNG compressed pixels") from error
    if len(raw) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Truncated or oversized PNG pixels")
    if any(raw[y * (width * channels + 1)] > 4 for y in range(height)):
        raise ValueError("Invalid PNG row filter")
    return width, height


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    w, h = validate(args.path)
    print(f"Validated device screenshot: {w} x {h}")
