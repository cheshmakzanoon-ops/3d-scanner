from tools.native_process import native_compile_env
from pathlib import Path
import subprocess

def test_actual_blend_kernel(tmp_path):
    root=Path(__file__).resolve().parents[1]
    binary=tmp_path/'blend'
    subprocess.run(['c++','-O2','-DNDEBUG','-std=c++20','-Wall','-Wextra','-Werror','-I',str(root/'reconstruction/include'),str(root/'reconstruction/tests/texture_blend_test.cpp'),str(root/'reconstruction/src/cancellation.cpp'),str(root/'reconstruction/src/photometric.cpp'),'-o',str(binary)],check=True, env=native_compile_env())
    subprocess.run([str(binary)],check=True)
