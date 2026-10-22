"""Compile and execute the actual typed BA parser, verifier and optimizer in Release mode."""
from tools.native_process import native_compile_env
from pathlib import Path
from tools.native_test_build import native_link_args, native_run_env
import os
import subprocess


def test_actual_typed_bundle_problem(tmp_path):
    root = Path(__file__).resolve().parents[1]
    executable = tmp_path / "typed-bundle"
    subprocess.run([
        "c++", "-std=c++20", "-O1", "-DNDEBUG", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
        "-I" + str(root / "reconstruction/include"),
        str(root / "reconstruction/tests/agents/003_bundle_problem_bridge_test.cpp"),
        str(root / "reconstruction/src/cancellation.cpp"),
        *native_link_args(), "-o", str(executable),
    ], check=True, timeout=120, env=native_compile_env())
    subprocess.run([str(executable)], env=native_run_env(),check=True, timeout=60)
