from tools.native_process import native_compile_env
from pathlib import Path
import subprocess

def test_surface_evidence_numerics(tmp_path):
    root=Path(__file__).resolve().parents[1]
    exe=tmp_path/'surface-evidence'
    subprocess.run(['g++','-std=c++20','-O2','-DNDEBUG','-Wall','-Wextra','-Wpedantic','-Werror','-I'+str(root/'reconstruction/include'),str(root/'reconstruction/tests/surface_evidence_test.cpp'),str(root/'reconstruction/src/cancellation.cpp'),str(root/'reconstruction/src/core.cpp'),'-o',str(exe)],check=True, env=native_compile_env())
    subprocess.run([str(exe)],check=True,timeout=30)
