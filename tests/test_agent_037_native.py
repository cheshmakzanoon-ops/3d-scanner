"""Compile and execute the actual owned native regression against this checkout's library."""
from tools.native_process import native_compile_env
from pathlib import Path
from tools.native_test_build import native_link_args, native_run_env
import os
import subprocess
import json
import struct


def test_agent_037_native_regression(tmp_path):
    root = Path(__file__).resolve().parents[1]
    executable = tmp_path / "initializer_degeneracy"
    subprocess.run(["c++", "-std=c++20", "-UNDEBUG", "-Wall", "-Wextra", "-Werror", "-pthread",
                    "-I" + str(root / "reconstruction/include"),
                    str(root / "reconstruction/tests/initializer_degeneracy_test.cpp"),
                    *native_link_args(),
                    "-o", str(executable)], check=True, timeout=120, env=native_compile_env())
    subprocess.run([str(executable),str(tmp_path)], env=native_run_env(),check=True, timeout=60)
    for kind in ["sparse", "dense"]:
        data=(tmp_path / f"typed-{kind}.glb").read_bytes()
        magic,version,length=struct.unpack_from("<III",data)
        assert magic==0x46546C67 and version==2 and length==len(data)
        json_size,chunk_type=struct.unpack_from("<II",data,12)
        assert chunk_type==0x4E4F534A
        asset=json.loads(data[20:20+json_size])
        assert asset["extras"]["representation"]==f"{kind}_points"
        assert asset["extras"]["scale"]=="arbitrary" and asset["extras"]["notSurfaceMesh"] is True
        assert asset["meshes"][0]["primitives"][0]["mode"]==0
        assert struct.unpack_from("<fff",data,20+json_size+8)==(1.,-2.,-3.)
        ply=(tmp_path / f"typed-{kind}.ply").read_text()
        assert f"comment representation {kind}_points" in ply
        assert "1 2 3 11 22 33" in ply
        if kind=="dense":
            assert "sparse reconstruction" not in ply
