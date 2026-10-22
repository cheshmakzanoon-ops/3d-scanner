from tools.native_process import native_compile_env
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
def test_strict_cache_budget_and_manifest(tmp_path):
    executable=tmp_path/"cache-test"
    subprocess.run(["g++","-std=c++20","-Wall","-Wextra","-Werror","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/depth_cache_manifest_test.cpp"),"-o",str(executable)],check=True, env=native_compile_env())
    subprocess.run([str(executable)],check=True,timeout=20)

def test_actual_process_termination_relaunch_and_cross_process_leases(tmp_path):
    executable=tmp_path/"recovery-test"
    subprocess.run(["g++","-std=c++20","-Wall","-Wextra","-Werror","-DRF_CHECKPOINT_TEST_HOOKS","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/agent_050_process_recovery.cpp"),str(ROOT/"reconstruction/src/depth_checkpoint.cpp"),str(ROOT/"reconstruction/src/cancellation.cpp"),"-o",str(executable)],check=True, env=native_compile_env())
    subprocess.run([str(executable)],check=True,timeout=40)
