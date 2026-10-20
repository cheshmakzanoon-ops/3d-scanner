from pathlib import Path
import subprocess

def test_mesh_topology_numerics(tmp_path):
    root=Path(__file__).resolve().parents[1]
    exe=tmp_path/'mesh-topology'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wpedantic','-Werror','-I'+str(root/'reconstruction/include'),str(root/'reconstruction/tests/mesh_topology_test.cpp'),str(root/'reconstruction/src/cancellation.cpp'),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=30)
