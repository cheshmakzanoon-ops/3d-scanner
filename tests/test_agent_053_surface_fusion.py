from tools.native_process import native_compile_env
from pathlib import Path
from tools.native_test_build import native_link_args, native_run_env
import subprocess

def test_actual_surface_fusion(tmp_path):
    root=Path(__file__).resolve().parents[1]
    exe=tmp_path/'surface-fusion'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wpedantic','-Werror','-I'+str(root/'reconstruction/include'),str(root/'reconstruction/tests/surface_fusion_test.cpp'),*native_link_args(),'-o',str(exe)],check=True, env=native_compile_env())
    subprocess.run([str(exe)],env=native_run_env(),check=True,timeout=60,cwd=tmp_path)
