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
        str(root / "reconstruction/tests/agent_066_asset_edit.cpp"), "-o", str(executable),
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
