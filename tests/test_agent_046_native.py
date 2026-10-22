"""Compile and execute the actual tiled CPU implementation's focused regression."""
from tools.native_process import native_compile_env
from pathlib import Path
from tools.native_test_build import native_link_args, native_run_env, native_compile_prefix
import os,subprocess
ROOT=Path(__file__).resolve().parents[1]
def test_robust_local_refinement_preserves_median_source_objective(tmp_path):
 executable=tmp_path/"robust-local"
 subprocess.run([*native_compile_prefix(),"-std=c++20","-O2","-DNDEBUG","-Wall","-Wextra","-Werror","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/agent_046_robust_local.cpp"),*native_link_args(),"-o",str(executable)],check=True, env=native_compile_env())
 subprocess.run([str(executable)],env=native_run_env(),check=True,timeout=120)

def test_admitted_tiled_cpu_equivalence(tmp_path):
 executable=tmp_path/"tile-test"
 subprocess.run([*native_compile_prefix(),"-std=c++20","-Wall","-Wextra","-Werror","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/depth_tiles_test.cpp"),*native_link_args(),"-o",str(executable)],check=True, env=native_compile_env())
 subprocess.run([str(executable)],env=native_run_env(),check=True,timeout=240)

def test_actual_dense_tile_process_kill_resume(tmp_path):
 executable=tmp_path/"dense-process"
 subprocess.run([*native_compile_prefix(),"-std=c++20","-Wall","-Wextra","-Werror","-DRF_CHECKPOINT_TEST_HOOKS","-I"+str(ROOT/"reconstruction/include"),str(ROOT/"reconstruction/tests/agent_046_tile_process.cpp"),str(ROOT/"reconstruction/src/depth_checkpoint.cpp"),*native_link_args(),"-Wl,--export-dynamic","-o",str(executable)],check=True, env=native_compile_env())
 subprocess.run([str(executable)],env=native_run_env(),check=True,timeout=120)
