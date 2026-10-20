"""Small independent typed PLY reader, separate from all serializer logic."""
from pathlib import Path
import os
import struct
import subprocess

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
TYPES = {"double": "d", "float": "f", "uchar": "B", "uint": "I"}


def read_ply(path):
    with path.open("rb") as stream:
        assert stream.readline() == b"ply\n"
        encoding = stream.readline().decode().split()[1]
        elements = []
        comments = []
        for raw in iter(stream.readline, b"end_header\n"):
            assert raw
            words = raw.decode().split()
            if words[0] == "comment":
                comments.append(" ".join(words[1:]))
            elif words[0] == "element":
                elements.append([words[1], int(words[2]), []])
            elif words[0] == "property":
                elements[-1][2].append(words[1:])
        values = {}
        for name, count, properties in elements:
            rows = []
            for _ in range(count):
                if encoding == "ascii":
                    words = iter(stream.readline().decode().split())
                row = {}
                for prop in properties:
                    if prop[0] == "list":
                        if encoding == "ascii":
                            size = int(next(words))
                            row[prop[3]] = [int(next(words)) for _ in range(size)]
                        else:
                            size, = struct.unpack("<" + TYPES[prop[1]], stream.read(struct.calcsize("<" + TYPES[prop[1]])))
                            row[prop[3]] = list(struct.unpack("<" + TYPES[prop[2]] * size, stream.read(struct.calcsize("<" + TYPES[prop[2]]) * size)))
                    elif encoding == "ascii":
                        row[prop[1]] = float(next(words)) if prop[0] in ("float", "double") else int(next(words))
                    else:
                        row[prop[1]], = struct.unpack("<" + TYPES[prop[0]], stream.read(struct.calcsize("<" + TYPES[prop[0]])))
                rows.append(row)
            values[name] = rows
        assert not stream.read(), "Unexpected trailing data or wrong property sizes"
    return values, comments


def test_ply_variants_independent_roundtrip_and_rejection(tmp_path):
    canonical = Path(os.environ.get("RF_CANONICAL_ASSET_ROOT", ROOT))
    assert (canonical / "reconstruction/src/asset_model.cpp").is_file(), (
        "Merge Agent 061 or set RF_CANONICAL_ASSET_ROOT to its checkout"
    )
    executable = tmp_path / "ply-fixtures"
    subprocess.run([
        "g++", "-std=c++20", "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-O2",
        "-I" + str(ROOT / "reconstruction/include"), "-I" + str(canonical / "reconstruction/include"),
        str(ROOT / "reconstruction/src/ply_export.cpp"), str(canonical / "reconstruction/src/asset_model.cpp"),
        str(ROOT / "reconstruction/tests/agent_064_ply_test.cpp"), "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable), str(tmp_path)], check=True)
    for filename in ("dense.ply", "sparse.ply", "surface.ply", "surface-ascii.ply"):
        values, comments = read_ply(tmp_path / filename)
        points = values["vertex"]
        assert len(points) == 3
        np.testing.assert_array_equal([[p[k] for k in ("x", "y", "z")] for p in points], [[.125, -1, -2], [3, 0, -1], [0, -4, -3]])
        np.testing.assert_array_equal([[p[k] for k in ("nx", "ny", "nz")] for p in points], [[0, 0, -1], [0, -1, 0], [1, 0, 0]])
        assert [[p[k] for k in ("red", "green", "blue")] for p in points] == [[255, 0, 2], [3, 240, 4], [5, 6, 220]]
        assert [p["confidence"] for p in points] == [.25, .5, .75]
        assert [p["supporting_views"] for p in points] == [2, 3, 9]
        assert "units arbitrary" in comments
        assert "source_sha256 " + "a" * 64 in comments
        if filename.startswith("surface"):
            assert values["face"] == [{"vertex_indices": [0, 1, 2]}]
            assert "representation observed_surface" in comments
            assert "photographic_texture omitted_in_ply" in comments
        else:
            assert "face" not in values
            expected = "sparse_points" if filename.startswith("sparse") else "dense_points"
            assert "representation " + expected in comments
