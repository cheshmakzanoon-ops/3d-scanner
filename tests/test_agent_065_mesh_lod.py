"""Compile and execute the real LOD module with the declared C10 dependency."""
from pathlib import Path
import os
import subprocess


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
        str(root / "reconstruction/tests/agent_065_mesh_lod.cpp"),
        "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True)
