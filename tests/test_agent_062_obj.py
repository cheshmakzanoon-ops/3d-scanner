"""Behavioral OBJ/MTL importer independent of the C++ writer; analytic fixtures."""
from pathlib import Path
import os
import subprocess
import zipfile

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def test_obj_package_geometry_texture_and_failures(tmp_path):
    canonical = Path(os.environ.get("RF_CANONICAL_ASSET_ROOT", ROOT))
    assert (canonical / "reconstruction/src/asset_model.cpp").is_file(), (
        "Merge published Agent 061 C10 first, or set RF_CANONICAL_ASSET_ROOT to its checkout"
    )
    executable = tmp_path / "obj-fixtures"
    subprocess.run([
        "g++", "-std=c++20", "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-O2",
        "-I" + str(ROOT / "reconstruction/include"),
        "-I" + str(canonical / "reconstruction/include"),
        str(ROOT / "reconstruction/src/obj_export.cpp"),
        str(canonical / "reconstruction/src/asset_model.cpp"),
        str(ROOT / "reconstruction/tests/agent_062_obj_test.cpp"), "-lz", "-o", str(executable),
    ], check=True)
    # Explicitly synthetic asymmetric checker: top red/green, bottom blue/white.
    checker = np.array([[[0, 0, 255], [0, 255, 0]], [[255, 0, 0], [255, 255, 255]]], dtype=np.uint8)
    source = tmp_path / "checker.png"
    assert cv2.imwrite(str(source), checker)
    subprocess.run([str(executable), str(tmp_path), str(source)], check=True)
    package = tmp_path / "package.zip"
    with zipfile.ZipFile(package, "w") as out:
        for suffix in ("obj", "mtl", "png"):
            out.write(tmp_path / ("asymmetric." + suffix), "asymmetric." + suffix)
    moved = tmp_path / "different-directory"
    with zipfile.ZipFile(package) as archive:
        archive.extractall(moved)
    vertices, normals, uv, faces = [], [], [], []
    mtl_name = None
    for line in (moved / "asymmetric.obj").read_text().splitlines():
        fields = line.split()
        if not fields or fields[0].startswith("#"):
            continue
        if fields[0] == "v":
            vertices.append([float(x) for x in fields[1:]])
        elif fields[0] == "vn":
            normals.append([float(x) for x in fields[1:]])
        elif fields[0] == "vt":
            uv.append([float(x) for x in fields[1:]])
        elif fields[0] == "f":
            faces.append([[int(x) - 1 for x in value.split("/")] for value in fields[1:]])
        elif fields[0] == "mtllib":
            assert mtl_name is None
            mtl_name = fields[1]
    np.testing.assert_array_equal(vertices, [[0, 0, 0], [2, 0, 0], [0, -3, 0], [0, 0, -4]])
    assert faces == [[[0, 0, 0], [1, 1, 1], [2, 2, 2]], [[0, 0, 0], [3, 3, 3], [1, 1, 1]]]
    np.testing.assert_array_equal(normals, [[0, 0, -1], [0, 0, -1], [0, 0, -1], [0, -1, 0]])
    # Rotation has determinant +1, so original triangle order is retained.
    face_normal = np.cross(np.array(vertices[1]) - vertices[0], np.array(vertices[2]) - vertices[0])
    assert np.dot(face_normal, normals[0]) > 0
    np.testing.assert_array_equal(uv, [[0, 1], [1, 1], [0, 0], [1, 0]])
    material = (moved / mtl_name).read_text()
    image_name = next(line.split(maxsplit=1)[1] for line in material.splitlines() if line.startswith("map_Kd "))
    assert Path(image_name).name == image_name
    np.testing.assert_array_equal(cv2.imread(str(moved / image_name)), checker)
    assert (moved / image_name).read_bytes() == source.read_bytes()
    point_records = [line.split()[0] for line in (tmp_path / "points.obj").read_text().splitlines() if line and not line.startswith("#")]
    assert point_records.count("p") == 4 and "f" not in point_records
    assert "dense_points" in (tmp_path / "points.obj").read_text()
