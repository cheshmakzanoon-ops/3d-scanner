"""Execute native edits with canonical C10 source, including reflected UV geometry."""
from pathlib import Path
import os
import subprocess


def test_native_edit_materialization(tmp_path):
    root = Path(__file__).resolve().parents[1]
    provider = Path(os.environ.get("RF_ASSET_CONTRACT_ROOT", root))
    assert (provider / "reconstruction/include/realityforge/asset_model.hpp").is_file(), "Integrate Agent 061 or set RF_ASSET_CONTRACT_ROOT"
    executable = tmp_path / "asset-edit"
    subprocess.run([
        "g++", "-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I" + str(root / "reconstruction/include"), "-I" + str(provider / "reconstruction/include"),
        str(root / "reconstruction/src/asset_edit.cpp"), str(provider / "reconstruction/src/asset_model.cpp"),
        str(root / "reconstruction/tests/agent_066_asset_edit.cpp"), "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True)
