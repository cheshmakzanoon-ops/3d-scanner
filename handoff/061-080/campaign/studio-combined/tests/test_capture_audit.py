"""Capture package input safety and reproducible overlap diagnostics."""
from __future__ import annotations
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"tools"))
from capture_audit import inspect_zip


def sample(tmp_path, *, altered=False, duplicate=False, unsafe=False):
    folder=ROOT/"benchmarks/fixtures/relief"
    files=[(folder/f"view-0{i}.jpg").read_bytes() for i in (1,2,3)]
    if duplicate:files[1]=files[0]
    photos=[]
    zip_path=tmp_path/"capture.zip"
    with zipfile.ZipFile(zip_path,"w",compression=zipfile.ZIP_DEFLATED) as archive:
        for i,data in enumerate(files):
            name="photos/../unsafe.jpg" if unsafe and i==0 else f"photos/photo-{i}.jpg"
            photos.append({"path":name,"sha256":hashlib.sha256(data).hexdigest() if not altered else "0"*64,
                           "focal35mm":26.0})
            archive.writestr(name,data)
        archive.writestr("session.json",json.dumps({"schema":1,"source":"synthetic_fixture_for_tests","photos":photos}))
    return zip_path


def test_capture_audit_labels_synthetic_and_checks_overlap(tmp_path):
    output=inspect_zip(sample(tmp_path))
    assert output["source"]=="synthetic_fixture_for_tests"
    assert output["kind"]=="capture_audit_not_reconstruction"
    assert output["photoCount"]==3
    assert len(output["neighborPairs"])==2
    assert output["photos"][0]["featureCount"]>100

def test_capture_audit_rejects_tampered_image(tmp_path):
    with pytest.raises(ValueError,match="checksum"):
        inspect_zip(sample(tmp_path,altered=True))

def test_capture_audit_rejects_duplicate_images(tmp_path):
    with pytest.raises(ValueError,match="Duplicate photograph"):
        inspect_zip(sample(tmp_path,duplicate=True))

def test_capture_audit_rejects_path_traversal(tmp_path):
    with pytest.raises(ValueError,match="unsafe"):
        inspect_zip(sample(tmp_path,unsafe=True))
