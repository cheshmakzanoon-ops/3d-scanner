#!/usr/bin/env python3
"""Offline audit for RealityForge's explicitly exported photo ZIP bundles.

Checks image identity, geometry metadata and consecutive-view overlap. It never
uploads files and never claims that a sparse result is a textured surface.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import struct
import zlib
import zipfile

MAX_IMAGES = 250
MAX_IMAGE_BYTES = 150 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
MAX_MANIFEST_BYTES = 200_000
MAX_PIXELS = 200_000_000
MAX_DECODE_PIXELS = 16_000_000
MAX_COMPRESSION_RATIO = 200


def _safe_path(value: str) -> bool:
    """Zip member names must be literal relative entries from the manifest."""
    return (isinstance(value, str) and len(value) <= 240
            and bool(re.fullmatch(r'photos/[A-Za-z0-9][A-Za-z0-9._-]*', value))
            and value.split('/')[1] not in ('.', '..'))


def _json(data: bytes):
    import numpy as np
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    def constant(value):
        raise ValueError('Nonfinite JSON number')
    def finite_float(value):
        result = float(value)
        if not np.isfinite(result):
            raise ValueError('Nonfinite JSON number')
        return result
    try:
        return json.loads(data, object_pairs_hook=unique, parse_constant=constant, parse_float=finite_float)
    except (UnicodeError, RecursionError) as error:
        raise ValueError('Malformed JSON') from error


def _preflight_directory(path):
    # Bound entry count/central-directory allocation BEFORE ZipFile builds objects.
    size = path.stat().st_size
    if not 22 <= size <= MAX_TOTAL_BYTES:
        raise ValueError('Archive size exceeds bounds')
    with path.open('rb') as stream:
        stream.seek(max(0, size - 65557))
        tail = stream.read(65557)
    offset = tail.rfind(b'PK\x05\x06')
    if offset < 0 or len(tail) - offset < 22:
        raise ValueError('Missing ZIP directory')
    _, disk, central_disk, count_disk, count, central_size, central_offset, comment = struct.unpack_from('<4s4H2IH', tail, offset)
    if (disk or central_disk or count_disk != count or not 1 <= count <= MAX_IMAGES + 2
            or central_size > 1024 * 1024 or central_offset + central_size > size
            or offset + 22 + comment != len(tail)):
        raise ValueError('Unsupported or oversized ZIP directory')


def _image_header(data):
    # JPEG and PNG are the explicit export-compatible subset. Never decode first.
    if data.startswith(b'\x89PNG\r\n\x1a\n'):
        if len(data) < 33 or data[12:16] != b'IHDR' or struct.unpack_from('>I', data, 8)[0] != 13:
            raise ValueError('Invalid PNG header')
        width, height = struct.unpack_from('>II', data, 16)
        kind = 'png'
    elif data.startswith(b'\xff\xd8'):
        offset, width, height = 2, None, None
        while offset < min(len(data), 1024 * 1024):
            if data[offset] != 255:
                raise ValueError('Invalid JPEG marker')
            while offset < len(data) and data[offset] == 255:
                offset += 1
            if offset >= len(data):
                break
            marker = data[offset]
            offset += 1
            if marker in (0xd9, 0xda):
                break
            if marker in (0x01, *range(0xd0, 0xd9)):
                continue
            if offset + 2 > len(data):
                break
            length = struct.unpack_from('>H', data, offset)[0]
            if length < 2 or offset + length > len(data):
                raise ValueError('Invalid JPEG segment')
            if marker in (0xc0, 0xc1, 0xc2):
                if length < 8:
                    raise ValueError('Invalid JPEG frame')
                height, width = struct.unpack_from('>HH', data, offset + 3)
                break
            offset += length
        if width is None:
            raise ValueError('Missing/bounded-out JPEG frame')
        kind = 'jpeg'
    else:
        raise ValueError('Unsupported image encoding; JPEG/PNG required')
    if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
        raise ValueError('One photograph exceeds pixel safety limit')
    if kind == 'png' and width * height > MAX_DECODE_PIXELS:
        raise ValueError('PNG exceeds bounded decode pixel limit')
    return width, height, kind


def _matching_count(a: np.ndarray | None, b: np.ndarray | None) -> int:
    import cv2
    if a is None or b is None or min(len(a), len(b)) < 2:
        return 0
    matcher = cv2.BFMatcher(cv2.NORM_L2)
    forward = matcher.knnMatch(a, b, k=2)
    backward = matcher.knnMatch(b, a, k=2)
    reverse = {m.queryIdx: m.trainIdx for match in backward if len(match) == 2
               for m, n in [match] if m.distance < .67*n.distance}
    return sum(len(match) == 2 and match[0].distance < .67*match[1].distance
               and reverse.get(match[0].trainIdx) == match[0].queryIdx
               for match in forward)


def inspect_zip(path: Path) -> dict:
    import cv2
    try:
        return _inspect_zip(path)
    except (zipfile.BadZipFile, cv2.error, zlib.error, NotImplementedError, RuntimeError) as error:
        raise ValueError('Malformed or unsupported capture payload') from error


def _inspect_zip(path: Path) -> dict:
    import cv2
    import numpy as np
    if not path.is_file():
        raise ValueError("Scan capture archive not found")
    _preflight_directory(path)
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_IMAGES + 2 or len({i.filename for i in entries}) != len(entries):
            raise ValueError('Duplicate/oversized ZIP entries')
        if any(i.is_dir() or i.flag_bits & 1 or i.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
               or stat.S_ISLNK(i.external_attr >> 16) or i.file_size > MAX_IMAGE_BYTES
               or (i.file_size and i.file_size > MAX_COMPRESSION_RATIO * max(1, i.compress_size)) for i in entries):
            raise ValueError('Unsafe or oversized/compression-bomb archive member')
        infos = {i.filename: i for i in entries}
        if "session.json" not in infos or len(infos) > MAX_IMAGES + 3:
            raise ValueError("Not a valid bounded RealityForge capture bundle")
        if any(not _safe_path(name) for name in infos if name != "session.json"
               and name != "camera-calibration-v1.json"):
            raise ValueError("Archive has unexpected or unsafe member")
        if sum(i.file_size for i in infos.values()) > MAX_TOTAL_BYTES:
            raise ValueError("Capture bundle uncompressed data is too large")
        if infos["session.json"].file_size > MAX_MANIFEST_BYTES:
            raise ValueError("Capture manifest is too large")
        manifest = _json(archive.read("session.json"))
        if not isinstance(manifest, dict) or type(manifest.get('schema')) is not int or manifest.get("schema") != 1 or not isinstance(manifest.get("photos"), list):
            raise ValueError("Unsupported manifest schema")
        photos = manifest["photos"]
        if not 3 <= len(photos) <= MAX_IMAGES:
            raise ValueError("At least three distinct photographs are required")
        for photo in photos:
            if (not isinstance(photo, dict) or not _safe_path(photo.get('path'))
                    or not isinstance(photo.get('sha256'), str) or not re.fullmatch('[0-9a-f]{64}', photo['sha256'])):
                raise ValueError('Malformed/unsafe photograph manifest record')
            f = photo.get('focal35mm')
            if f is not None and (type(f) not in (int, float) or not np.isfinite(f) or not 10 <= f <= 600):
                raise ValueError('Invalid focal metadata')
        if len(set(p.get("path") for p in photos)) != len(photos):
            raise ValueError("Duplicate paths in capture manifest")
        if set(infos) - {'session.json', 'camera-calibration-v1.json'} != {p['path'] for p in photos}:
            raise ValueError('Unreferenced or missing photograph members')
        if 'camera-calibration-v1.json' in infos:
            if infos['camera-calibration-v1.json'].file_size > MAX_MANIFEST_BYTES:
                raise ValueError('Calibration metadata exceeds bound')
            calibration = _json(archive.read('camera-calibration-v1.json'))
            if not isinstance(calibration, dict):
                raise ValueError('Malformed calibration metadata')
        items, desc, warnings = [], [], []
        seen = set()
        sizes, known_focals = [], []
        sift = cv2.SIFT_create(nfeatures=3500)
        for p in photos:
            member = p.get("path")
            if not isinstance(member, str) or not _safe_path(member) or member not in infos:
                raise ValueError("Missing or unsafe photo in scan package")
            if infos[member].file_size > MAX_IMAGE_BYTES:
                raise ValueError("One scan photograph exceeds the safety bound")
            data = archive.read(member)
            digest = hashlib.sha256(data).hexdigest()
            if digest != p.get("sha256"):
                raise ValueError("Photograph checksum mismatch: " + member)
            if digest in seen:
                raise ValueError("Duplicate photograph bytes in scan package")
            seen.add(digest)
            w, h, kind = _image_header(data)
            reduced = kind == 'jpeg' and w*h > MAX_DECODE_PIXELS
            flag = (cv2.IMREAD_REDUCED_COLOR_8 if reduced else cv2.IMREAD_COLOR) | cv2.IMREAD_IGNORE_ORIENTATION
            image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), flag)
            if image is None or image.size == 0:
                raise ValueError("Photograph failed decoding: " + member)
            factor = 8 if reduced else 1
            if image.shape[:2] != ((h+factor-1)//factor, (w+factor-1)//factor) or image.shape[0]*image.shape[1] > MAX_DECODE_PIXELS:
                raise ValueError('Decoded image disagrees with bounded header')
            max_edge = max(image.shape[:2])
            if max_edge > 1600:
                scale = 1600 / max_edge
                image = cv2.resize(image, (round(image.shape[1]*scale),round(image.shape[0]*scale)))
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            key, d = sift.detectAndCompute(gray,None)
            lap = float(cv2.Laplacian(gray, cv2.CV_64F).var())
            f = p.get("focal35mm")
            if f is not None:
                if not isinstance(f,(int,float)) or not np.isfinite(f) or not 10 <= f <= 600:
                    raise ValueError("Invalid focal metadata: " + member)
                known_focals.append(float(f))
            items.append({"path": member,"sha256": digest,"width":w,"height":h,
                          "featureCount":len(key),"sharpnessVariance":round(lap,2),
                          "focal35mm":f})
            desc.append(d)
            sizes.append((w,h))
        if len(set(round(w/h,4) for w,h in sizes)) > 1:
            warnings.append("Mixed photo aspect ratios: image crops or orientations may differ")
        if len(known_focals)>1 and max(known_focals)/min(known_focals)>1.08:
            warnings.append("EXIF indicates different lenses/zoom: one calibration profile is unsafe")
        overlaps = [{"from":items[i]["path"],"to":items[i+1]["path"],
                     "reciprocalSiftMatches":_matching_count(desc[i],desc[i+1])}
                    for i in range(len(desc)-1)]
        if any(x["reciprocalSiftMatches"] < 40 for x in overlaps):
            warnings.append("One or more neighboring photos have weak feature overlap; retake intermediate views")
        if any(x["featureCount"] < 100 for x in items):
            warnings.append("One or more photos have few distinctive features")
        if len(photos) < 8:
            warnings.append("Fewer than eight photos: this is an early sparse check, not a complete object scan")
        status = "review" if warnings else "candidate_for_sparse_testing"
        return {"schema":1,"kind":"capture_audit_not_reconstruction",
                "source":manifest.get("source", "unknown"),
                "photos":items,"neighborPairs":overlaps,"warnings":warnings,
                "photoCount":len(items),"status":status,
                "calibrationIncluded":"camera-calibration-v1.json" in infos,
                "doesNotProve":["camera calibration validity", "correct 3D geometry",
                                "dense surface reconstruction", "mesh or texture quality"]}


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("archive",type=Path)
    ap.add_argument("--output",type=Path)
    args=ap.parse_args()
    report=inspect_zip(args.archive)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps({k:v for k,v in report.items() if k!="photos"},indent=2))

if __name__=="__main__":main()
