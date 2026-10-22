"""Compiled numerical behavior, not a source-string contract."""
from tools.native_process import native_compile_env
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
def test_agent_047_numerical_kernel(tmp_path):
    executable=tmp_path/"kernel"
    subprocess.run(["g++","-std=c++20","-Wall","-Wextra","-Werror","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/depth_refinement_test.cpp"),"-o",str(executable)],check=True, env=native_compile_env())
    subprocess.run([str(executable)],check=True,timeout=20)
