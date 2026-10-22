"""Compile and execute the real LOD module with the declared C10 dependency."""
from tools.native_process import native_compile_env
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
    topology = Path(os.environ.get("RF_TOPOLOGY_CONTRACT_ROOT", root))
    extra = []
    if (topology / "reconstruction/include/realityforge/mesh_topology.hpp").is_file():
        extra = ["-DRF_WITH_TOPOLOGY", "-I" + str(topology / "reconstruction/include"),
                 str(root / "reconstruction/src/cancellation.cpp")]
    subprocess.run([
        "g++", "-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I" + str(root / "reconstruction/include"),
        "-I" + str(provider / "reconstruction/include"),
        str(root / "reconstruction/src/mesh_lod.cpp"),
        str(provider / "reconstruction/src/asset_model.cpp"),
        str(provider / "reconstruction/src/glb_export.cpp"),
        str(root / "reconstruction/tests/agent_065_mesh_lod.cpp"),
        *extra, "-lz", "-o", str(executable),
    ], check=True, env=native_compile_env())
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
    binary_start = 28 + json_size
    def values(accessor, fmt, width):
        view = document["bufferViews"][accessor["bufferView"]]
        offset = binary_start + view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
        stride = view.get("byteStride", struct.calcsize(fmt) * width)
        return [struct.unpack_from("<" + fmt * width, data, offset + i * stride) for i in range(accessor["count"])]
    coordinates = values(positions, "f", 3)
    faces = [v[0] for v in values(indices, "I", 1)]
    for i in range(0, len(faces), 3):
        a, b, c = (coordinates[j] for j in faces[i:i + 3])
        # Internal +Z normal becomes -Z after the proper glTF rotation; winding stays consistent.
        assert (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) < 0
