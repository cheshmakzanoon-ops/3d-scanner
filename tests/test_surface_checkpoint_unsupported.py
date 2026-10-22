"""Execute the production publisher against unsupported no-replace syscall results."""
from pathlib import Path
import subprocess

from tools.native_process import native_compile_env
from tools.native_test_build import native_compile_prefix, native_link_args, native_run_env

ROOT = Path(__file__).resolve().parents[1]


def test_unsupported_surface_publication_preserves_depth_files(tmp_path):
    executable = tmp_path / "surface-checkpoint-unsupported"
    subprocess.run([
        *native_compile_prefix(), "-std=c++20", "-O1",
        "-I" + str(ROOT / "reconstruction/include"),
        str(ROOT / "reconstruction/tests/surface_checkpoint_unsupported_test.cpp"),
        *native_link_args(), "-o", str(executable),
    ], check=True, timeout=60, env=native_compile_env())
    subprocess.run([str(executable)], check=True, timeout=60, env=native_run_env())
