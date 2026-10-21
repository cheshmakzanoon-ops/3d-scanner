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
import capture_audit
import struct


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


def rewrite(path, transform):
    with zipfile.ZipFile(path) as archive:
        entries = [(i.filename, archive.read(i)) for i in archive.infolist()]
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in transform(entries):
            archive.writestr(name, data)
    return path


@pytest.mark.parametrize('value', [None, [], 3, True, 'x', {'schema': True, 'photos': []}, {'schema': 1, 'photos': [None]*3}, {'schema': 1, 'photos': [{'path': []}]*3}])
def test_malformed_manifest_types_fail_predictably(tmp_path, value):
    path = sample(tmp_path)
    rewrite(path, lambda entries: [(name, json.dumps(value).encode() if name == 'session.json' else data) for name, data in entries])
    with pytest.raises(ValueError):
        inspect_zip(path)


@pytest.mark.parametrize('name', ['session.json', 'photos/photo-0.jpg'])
def test_duplicate_archive_entries_rejected_before_read(tmp_path, name, monkeypatch):
    path = sample(tmp_path)
    with zipfile.ZipFile(path, 'a') as archive:
        with pytest.warns(UserWarning):
            archive.writestr(name, b'x')
    monkeypatch.setattr(capture_audit.cv2, 'imdecode', lambda *args: pytest.fail('decode reached'))
    with pytest.raises(ValueError, match='Duplicate'):
        inspect_zip(path)


@pytest.mark.parametrize('member', ['photos//photo-0.jpg', 'photos/./photo-0.jpg', 'photos/../a.jpg', 'photos/a\\b.jpg', 'photos/a:b.jpg', 'photos/a\x00b.jpg'])
def test_literal_paths_not_normalized(member):
    assert not capture_audit._safe_path(member)


def test_unreferenced_image_rejected(tmp_path):
    path = sample(tmp_path)
    with zipfile.ZipFile(path, 'a') as archive:
        archive.writestr('photos/extra.jpg', b'extra')
    with pytest.raises(ValueError, match='Unreferenced'):
        inspect_zip(path)


def test_compression_bomb_rejected_before_decode(tmp_path, monkeypatch):
    path = sample(tmp_path)
    with zipfile.ZipFile(path, 'a') as archive:
        archive.writestr('photos/bomb.jpg', b'0' * 100_000, compress_type=zipfile.ZIP_DEFLATED)
    monkeypatch.setattr(capture_audit.cv2, 'imdecode', lambda *args: pytest.fail('decode reached'))
    with pytest.raises(ValueError, match='compression-bomb'):
        inspect_zip(path)


def test_pixel_bomb_header_rejected_before_decode(tmp_path, monkeypatch):
    path = sample(tmp_path)
    header = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', 100_000, 100_000) + b'\x08\x02\x00\x00\x00' + b'xxxx'
    def change(entries):
        manifest = json.loads(dict(entries)['session.json'])
        manifest['photos'][0]['sha256'] = hashlib.sha256(header).hexdigest()
        return [(name, header if name == 'photos/photo-0.jpg' else json.dumps(manifest).encode() if name == 'session.json' else data) for name, data in entries]
    rewrite(path, change)
    monkeypatch.setattr(capture_audit.cv2, 'imdecode', lambda *args: pytest.fail('decode reached'))
    with pytest.raises(ValueError, match='pixel safety'):
        inspect_zip(path)


def test_duplicate_json_and_nonfinite_rejected(tmp_path):
    for data in (b'{"schema":1,"schema":1}', b'{"schema":1,"photos":NaN}'):
        path = sample(tmp_path)
        rewrite(path, lambda entries: [(name, data if name == 'session.json' else payload) for name, payload in entries])
        with pytest.raises(ValueError):
            inspect_zip(path)


def test_excess_directory_entries_rejected_before_zipfile(tmp_path, monkeypatch):
    path = tmp_path / 'many.zip'
    with zipfile.ZipFile(path, 'w') as archive:
        for i in range(254):
            archive.writestr(str(i), b'')
    monkeypatch.setattr(capture_audit.zipfile, 'ZipFile', lambda *args: pytest.fail('ZIP allocation reached'))
    with pytest.raises(ValueError, match='directory'):
        inspect_zip(path)
