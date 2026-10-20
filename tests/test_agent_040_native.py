"""Compile and execute the actual owned native regression against this checkout's library."""
from pathlib import Path
import os
import subprocess


def test_agent_040_native_regression(tmp_path):
    root = Path(__file__).resolve().parents[1]
    library = root / "build/host"
    executable = tmp_path / "triangulation_conditioning"
    subprocess.run(["c++", "-std=c++20", "-UNDEBUG", "-Wall", "-Wextra", "-Werror", "-pthread",
                    "-I" + str(root / "reconstruction/include"),
                    str(root / "reconstruction/tests/triangulation_conditioning_test.cpp"),
                    "-L" + str(library), "-lrealityforge", "-Wl,-rpath," + str(library),
                    "-o", str(executable)], check=True, timeout=120)
    subprocess.run([str(executable)], check=True, timeout=60)
