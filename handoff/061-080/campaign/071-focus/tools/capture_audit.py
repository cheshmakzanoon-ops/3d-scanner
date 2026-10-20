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
import zipfile

import cv2
import numpy as np

MAX_IMAGES = 250
MAX_IMAGE_BYTES = 150 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024


def _safe_path(value: str) -> bool:
    """Zip member names must be literal relative entries from the manifest."""
    from pathlib import PurePosixPath
    p = PurePosixPath(value)
    return (bool(value) and not p.is_absolute() and '\\' not in value
            and all(x not in ("", ".", "..") for x in p.parts)
            and p.parts[0] == 'photos' and len(p.parts) == 2)


def _matching_count(a: np.ndarray | None, b: np.ndarray | None) -> int:
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
    if not path.is_file():
        raise ValueError("Scan capture archive not found")
    with zipfile.ZipFile(path) as archive:
        infos = {i.filename: i for i in archive.infolist() if not i.is_dir()}
        if "session.json" not in infos or len(infos) > MAX_IMAGES + 3:
            raise ValueError("Not a valid bounded RealityForge capture bundle")
        if any(not _safe_path(name) for name in infos if name != "session.json"
               and name != "camera-calibration-v1.json"):
            raise ValueError("Archive has unexpected or unsafe member")
        if sum(i.file_size for i in infos.values()) > MAX_TOTAL_BYTES:
            raise ValueError("Capture bundle uncompressed data is too large")
        if infos["session.json"].file_size > 200_000:
            raise ValueError("Capture manifest is too large")
        manifest = json.loads(archive.read("session.json"))
        if manifest.get("schema") != 1 or not isinstance(manifest.get("photos"), list):
            raise ValueError("Unsupported manifest schema")
        photos = manifest["photos"]
        if not 3 <= len(photos) <= MAX_IMAGES:
            raise ValueError("At least three distinct photographs are required")
        if len(set(p.get("path") for p in photos)) != len(photos):
            raise ValueError("Duplicate paths in capture manifest")
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
            image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None or image.size == 0:
                raise ValueError("Photograph failed decoding: " + member)
            h, w = image.shape[:2]
            if w*h > 200_000_000:
                raise ValueError("One photograph exceeds pixel safety limit")
            max_edge = max(w,h)
            if max_edge > 1600:
                scale = 1600 / max_edge
                image = cv2.resize(image, (round(w*scale),round(h*scale)))
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
