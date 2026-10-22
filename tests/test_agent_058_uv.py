from tools.native_process import native_compile_env
from pathlib import Path
import subprocess
import shutil

def test_actual_uv_charts(tmp_path):
    root=Path(__file__).resolve().parents[1]
    binary=tmp_path/'uv'
    subprocess.run(['c++','-O2','-DNDEBUG','-std=c++20','-Wall','-Wextra','-Werror','-I',str(root/'reconstruction/include'),str(root/'reconstruction/tests/uv_atlas_test.cpp'),str(root/'reconstruction/src/cancellation.cpp'),'-o',str(binary)],check=True, env=native_compile_env())
    subprocess.run([str(binary)],check=True)
    retained=root/"build/agent058/uv-profile"
    retained.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(binary,retained)
