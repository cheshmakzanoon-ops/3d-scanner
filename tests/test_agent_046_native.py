"""Compile and execute the actual tiled CPU implementation's focused regression."""
from pathlib import Path
import os,subprocess
ROOT=Path(__file__).resolve().parents[1]
def test_admitted_tiled_cpu_equivalence(tmp_path):
 executable=tmp_path/"tile-test"
 subprocess.run(["g++","-std=c++20","-Wall","-Wextra","-Werror","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/depth_tiles_test.cpp"),"-L"+str(ROOT/"build/host"),"-lrealityforge","-Wl,-rpath,"+str(ROOT/"build/host"),"-o",str(executable)],check=True)
 subprocess.run([str(executable)],check=True,timeout=120)
