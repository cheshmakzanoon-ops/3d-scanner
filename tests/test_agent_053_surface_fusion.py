from pathlib import Path
import subprocess

def test_actual_surface_fusion(tmp_path):
    root=Path(__file__).resolve().parents[1]
    library=root/'build/host'
    exe=tmp_path/'surface-fusion'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wpedantic','-Werror','-I'+str(root/'reconstruction/include'),str(root/'reconstruction/tests/surface_fusion_test.cpp'),'-L'+str(library),'-Wl,-rpath,'+str(library),'-lrealityforge','-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=60,cwd=tmp_path)
