from pathlib import Path
import subprocess

def test_actual_cleanup_kernel(tmp_path):
    root = Path(__file__).resolve().parents[1]
    binary = tmp_path / 'cleanup'
    subprocess.run(['c++', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-I', str(root/'reconstruction/include'), str(root/'reconstruction/tests/mesh_cleanup_test.cpp'), str(root/'reconstruction/src/cancellation.cpp'), '-o', str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
