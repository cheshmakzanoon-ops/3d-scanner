"""Compile and execute the native progress behavior, rather than inspect source."""
from pathlib import Path
import subprocess

def test_scoped_native_progress(tmp_path):
    root = Path(__file__).resolve().parents[1]
    output = tmp_path / 'progress'
    subprocess.run(['g++', '-std=c++20', '-pthread', '-Wall', '-Wextra', '-Wpedantic', '-Werror', '-I'+str(root/'reconstruction/include'), str(root/'reconstruction/tests/progress_test.cpp'), str(root/'reconstruction/src/cancellation.cpp'), '-o', str(output)], check=True)
    subprocess.run([str(output)], check=True, timeout=20)
