"""Execute native edits with canonical C10 source, including reflected UV geometry."""
from pathlib import Path
import os
import subprocess
import json
import struct


def test_native_edit_materialization(tmp_path):
    root = Path(__file__).resolve().parents[1]
    provider = Path(os.environ.get("RF_ASSET_CONTRACT_ROOT", root))
    assert (provider / "reconstruction/include/realityforge/asset_model.hpp").is_file(), "Integrate Agent 061 or set RF_ASSET_CONTRACT_ROOT"
    executable = tmp_path / "asset-edit"
    subprocess.run([
        "g++", "-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I" + str(root / "reconstruction/include"), "-I" + str(provider / "reconstruction/include"),
        str(root / "reconstruction/src/asset_edit.cpp"), str(provider / "reconstruction/src/asset_model.cpp"),
        str(provider / "reconstruction/src/glb_export.cpp"),
        str(root / "reconstruction/tests/agent_066_asset_edit.cpp"), "-lz", "-o", str(executable),
    ], check=True)
    output = tmp_path / "metric-edit.glb"
    subprocess.run([str(executable), str(output)], check=True)
    data = output.read_bytes()
    magic, version, size, json_size, json_kind = struct.unpack_from("<5I", data)
    assert (magic, version, size, json_kind) == (0x46546C67, 2, len(data), 0x4E4F534A)
    document = json.loads(data[20:20 + json_size])
    primitive = document["meshes"][0]["primitives"][0]
    index_accessor = document["accessors"][primitive["indices"]]
    positions = document["accessors"][primitive["attributes"]["POSITION"]]
    assert primitive["mode"] == 4 and index_accessor["count"] == 6
    assert positions["min"][0] == 0 and abs(positions["min"][1] + .4) < 1e-6 and positions["min"][2] == 0
    assert abs(positions["max"][0] - 2.4) < 1e-6
    reflected = (tmp_path / "reflected-edit.glb").read_bytes()
    length = struct.unpack_from("<I", reflected, 12)[0]
    doc = json.loads(reflected[20:20 + length])
    primitive = doc["meshes"][0]["primitives"][0]
    def values(index, fmt, width):
        accessor = doc["accessors"][index]
        view = doc["bufferViews"][accessor["bufferView"]]
        offset = 28 + length + view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
        stride = view.get("byteStride", struct.calcsize(fmt) * width)
        return [struct.unpack_from("<" + fmt * width, reflected, offset + i * stride) for i in range(accessor["count"])]
    vertices = values(primitive["attributes"]["POSITION"], "f", 3)
    normals = values(primitive["attributes"]["NORMAL"], "f", 3)
    indices = [v[0] for v in values(primitive["indices"], "I", 1)]
    assert all(n == (0, 0, -1) for n in normals)
    for i in range(0, len(indices), 3):
        a, b, c = (vertices[j] for j in indices[i:i + 3])
        assert (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) < 0
