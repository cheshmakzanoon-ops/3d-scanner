"""Compile and execute the real LOD module with the declared C10 dependency."""
from pathlib import Path
import os
import subprocess
import json
import struct


def test_constrained_lod_runtime(tmp_path):
    root = Path(__file__).resolve().parents[1]
    provider = Path(os.environ.get("RF_ASSET_CONTRACT_ROOT", root))
    header = provider / "reconstruction/include/realityforge/asset_model.hpp"
    assert header.is_file(), "Integrate Agent 061 first, or set RF_ASSET_CONTRACT_ROOT to its checkout"
    executable = tmp_path / "mesh-lod"
    subprocess.run([
        "g++", "-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I" + str(root / "reconstruction/include"),
        "-I" + str(provider / "reconstruction/include"),
        str(root / "reconstruction/src/mesh_lod.cpp"),
        str(provider / "reconstruction/src/asset_model.cpp"),
        str(provider / "reconstruction/src/glb_export.cpp"),
        str(root / "reconstruction/tests/agent_065_mesh_lod.cpp"),
        "-o", str(executable),
    ], check=True)
    output = tmp_path / "lod.glb"
    subprocess.run([str(executable), str(output)], check=True)
    data = output.read_bytes()
    magic, version, size, json_size, json_kind = struct.unpack_from("<5I", data)
    assert (magic, version, size, json_kind) == (0x46546C67, 2, len(data), 0x4E4F534A)
    document = json.loads(data[20:20 + json_size])
    primitive = document["meshes"][0]["primitives"][0]
    indices = document["accessors"][primitive["indices"]]
    positions = document["accessors"][primitive["attributes"]["POSITION"]]
    assert primitive["mode"] == 4 and indices["count"] == 24 * 3
    assert positions["min"] == [0, -4, 0] and positions["max"] == [4, 0, 0]
