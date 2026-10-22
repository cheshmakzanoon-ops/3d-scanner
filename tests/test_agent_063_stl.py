"""Independent binary STL reader exercises scaling, normals and failure paths."""
from tools.native_process import native_compile_env
from pathlib import Path
import os
import struct
import subprocess

import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def test_binary_stl_independent_import_and_rejections(tmp_path):
    canonical = Path(os.environ.get("RF_CANONICAL_ASSET_ROOT", ROOT))
    assert (canonical / "reconstruction/src/asset_model.cpp").is_file(), (
        "Merge Agent 061, or set RF_CANONICAL_ASSET_ROOT to its checkout"
    )
    executable = tmp_path / "stl-fixtures"
    subprocess.run([
        "g++", "-std=c++20", "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-O2",
        "-I" + str(ROOT / "reconstruction/include"), "-I" + str(canonical / "reconstruction/include"),
        str(ROOT / "reconstruction/src/stl_export.cpp"), str(canonical / "reconstruction/src/asset_model.cpp"),
        str(ROOT / "reconstruction/tests/agent_063_stl_test.cpp"), "-lz", "-o", str(executable),
    ], check=True, env=native_compile_env())
    output = tmp_path / "asymmetric.stl"
    subprocess.run([str(executable), str(output)], check=True)
    data = output.read_bytes()
    count, = struct.unpack_from("<I", data, 80)
    assert count == 2 and len(data) == 84 + count * 50
    expected = [[[0, 0, 0], [20, 0, 0], [0, -30, 0]], [[0, 0, 0], [0, 0, -40], [20, 0, 0]]]
    for i in range(count):
        values = struct.unpack_from("<12fH", data, 84 + i * 50)
        normal = np.array(values[:3])
        triangle = np.array(values[3:12]).reshape(3, 3)
        np.testing.assert_array_equal(triangle, expected[i])
        computed = np.cross(triangle[1] - triangle[0], triangle[2] - triangle[0])
        computed /= np.linalg.norm(computed)
        np.testing.assert_allclose(normal, computed, atol=1e-7)
        assert values[-1] == 0  # No invented color/material attribute encoding.
