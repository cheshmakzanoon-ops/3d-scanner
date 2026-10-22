"""Compile and execute the actual owned native regression against this checkout's library."""
from tools.native_process import native_compile_env
from pathlib import Path
from tools.native_test_build import native_link_args, native_run_env
import os
import subprocess


def test_agent_038_native_regression(tmp_path):
    root = Path(__file__).resolve().parents[1]
    executable = tmp_path / "pnp_robustness"
    subprocess.run(["c++", "-std=c++20", "-UNDEBUG", "-Wall", "-Wextra", "-Werror", "-pthread",
                    "-I" + str(root / "reconstruction/include"),
                    str(root / "reconstruction/tests/pnp_robustness_test.cpp"),
                    *native_link_args(),
                    "-o", str(executable)], check=True, timeout=120, env=native_compile_env())
    subprocess.run([str(executable)], env=native_run_env(),check=True, timeout=60)
