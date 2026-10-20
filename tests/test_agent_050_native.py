from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
def test_strict_cache_budget_and_manifest(tmp_path):
    executable=tmp_path/"cache-test"
    subprocess.run(["g++","-std=c++20","-Wall","-Wextra","-Werror","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/depth_cache_manifest_test.cpp"),"-o",str(executable)],check=True)
    subprocess.run([str(executable)],check=True,timeout=20)
