"""Native export path boundaries; no geometry quality claims from these tests."""
from __future__ import annotations

import ctypes
import os
from pathlib import Path
import struct
import sys

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from native_api import NativeEngine


def observations():
    rng = np.random.default_rng(82)
    xyz = np.column_stack((rng.uniform(-1, 1, (180, 2)), rng.uniform(3, 8, 180)))
    angle = .08
    rotation = np.array([[np.cos(angle), 0, np.sin(angle)], [0, 1, 0],
                         [-np.sin(angle), 0, np.cos(angle)]])
    second = xyz @ rotation.T + [-1, 0, 0]
    return np.column_stack((xyz[:, :2] / xyz[:, 2, None],
                            second[:, :2] / second[:, 2, None],
                            rng.integers(0, 256, (180, 3))))


@pytest.mark.parametrize("argument", ["glb", "ply"])
def test_real_native_export_rejects_nul_without_overwriting_prefix(tmp_path, argument):
    target = tmp_path / ("existing." + argument)
    original = b"original artifact must survive"
    target.write_bytes(original)
    malformed = Path(str(target) + "\0unintended-suffix")
    # This is the actual FFI hazard: c_char_p accepts and truncates the bytes.
    assert ctypes.c_char_p(os.fsencode(malformed)).value == os.fsencode(target)
    engine = NativeEngine()
    with pytest.raises(ValueError, match="NUL"):
        engine.reconstruct(observations(), **{argument: malformed})
    assert target.read_bytes() == original


class NoNativeCalls:
    def __getattr__(self, name):
        raise AssertionError("Invalid output path reached native boundary: " + name)


@pytest.mark.parametrize("method,argument", [
    ("reconstruct", "glb"), ("reconstruct", "ply"),
    ("surface", "glb"), ("surface", "ply"),
    ("textured_surface", "mesh_glb"), ("textured_surface", "mesh_ply"),
    ("textured_surface", "textured_glb"), ("textured_surface", "atlas_png"),
])
@pytest.mark.parametrize("path_kind", [str, bytes, Path])
def test_every_export_argument_rejects_nul_before_native_call(method, argument, path_kind):
    engine = NativeEngine.__new__(NativeEngine)
    engine.library = NoNativeCalls()
    text = "artifact\0suffix"
    malformed = text.encode() if path_kind is bytes else path_kind(text)
    images = np.zeros((2, 32, 32, 4), dtype=np.uint8)
    cameras = np.zeros((2, 16))
    args = {"reconstruct": (np.zeros((20, 7)),),
            "surface": (images, cameras, 1., 4.),
            "textured_surface": (images, images, cameras, 1., 4.)}[method]
    with pytest.raises(ValueError, match="NUL"):
        getattr(engine, method)(*args, **{argument: malformed})


class Filename:
    def __init__(self, value):
        self.value = value

    def __fspath__(self):
        return self.value


def test_real_native_exports_preserve_unicode_and_pathlike_support(tmp_path):
    glb = tmp_path / "مدل café.glb"
    ply = tmp_path / "نقاط café.ply"
    engine = NativeEngine()
    result = engine.reconstruct(observations(), glb=Filename(str(glb)), ply=Filename(os.fsencode(ply)))
    raw = glb.read_bytes()
    assert struct.unpack_from("<4sII", raw) == (b"glTF", 2, len(raw))
    assert f"element vertex {result['pointCount']}" in ply.read_text()
    # Omitted outputs retain their existing no-export semantics.
    assert engine.reconstruct(observations(), glb=None, ply=None)["pointCount"] == result["pointCount"]
