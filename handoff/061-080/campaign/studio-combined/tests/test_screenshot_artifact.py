"""Tests that an ADB command's success is not confused with valid image evidence."""
import importlib.util
from pathlib import Path
import struct
import zlib
import pytest

spec=importlib.util.spec_from_file_location("check_screenshot",Path(__file__).resolve().parents[1]/"tools/check_screenshot.py")
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def chunk(kind, data):
    return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)


def fixture():
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR",struct.pack(">IIBBBBB",128,128,8,2,0,0,0))
            + chunk(b"IDAT",zlib.compress((b"\0"+b"\x10\x20\x30"*128)*128)) + chunk(b"IEND",b""))


def test_valid_png_is_checked(tmp_path):
    f=tmp_path/"valid.png";f.write_bytes(fixture())
    assert mod.validate(f)==(128,128)


@pytest.mark.parametrize("data",[
    b"run-as: unknown package: com.realityforge.scanner\n",
    fixture()[:-10], fixture()[:-1]+b"x", fixture()+b"unwanted output",
])
def test_adb_errors_and_corrupt_images_are_rejected(tmp_path,data):
    f=tmp_path/"invalid.png";f.write_bytes(data)
    with pytest.raises(ValueError):mod.validate(f)
