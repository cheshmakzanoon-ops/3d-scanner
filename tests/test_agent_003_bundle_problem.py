"""Compile and execute the actual typed BA parser, verifier and optimizer in Release mode."""
from pathlib import Path
import os
import subprocess


def test_actual_typed_bundle_problem(tmp_path):
    root = Path(__file__).resolve().parents[1]
    native = Path(os.environ.get("RF_NATIVE_LIBRARY", root / "build/host/librealityforge.so"))
    assert native.is_file(), "Build the actual host realityforge library first"
    executable = tmp_path / "typed-bundle"
    subprocess.run([
        "c++", "-std=c++20", "-O1", "-DNDEBUG", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
        "-I" + str(root / "reconstruction/include"),
        str(root / "reconstruction/tests/agents/003_bundle_problem_bridge_test.cpp"),
        str(root / "reconstruction/src/cancellation.cpp"),
        "-L" + str(native.parent), "-Wl,-rpath," + str(native.parent), "-lrealityforge", "-o", str(executable),
    ], check=True, timeout=120)
    subprocess.run([str(executable)], check=True, timeout=60)
