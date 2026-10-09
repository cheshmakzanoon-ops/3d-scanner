"""Typed ctypes binding to the same native library used by the Android JNI bridge."""
from __future__ import annotations
import ctypes
import json
import os
import platform
from pathlib import Path
from typing import Any
import numpy as np

ROOT=Path(__file__).resolve().parents[1]


def library_path() -> Path:
    override=os.environ.get("RF_NATIVE_LIBRARY")
    if override:
        return Path(override)
    name={"Linux":"librealityforge.so","Darwin":"librealityforge.dylib","Windows":"realityforge.dll"}[platform.system()]
    for parent in [ROOT/"build/host",ROOT/"build/host/Release",ROOT/"build"]:
        path=parent/name
        if path.exists(): return path
    raise FileNotFoundError("Build the C++ library first: cmake -S . -B build/host && cmake --build build/host")


class NativeEngine:
    def __init__(self,path:Path|None=None):
        self.library=ctypes.CDLL(str(path or library_path()))
        self.library.rf_reconstruct_json.argtypes=[ctypes.POINTER(ctypes.c_double),ctypes.c_size_t,ctypes.c_double,ctypes.c_char_p,ctypes.c_char_p]
        self.library.rf_reconstruct_json.restype=ctypes.c_void_p
        self.library.rf_register_camera_json.argtypes=[ctypes.POINTER(ctypes.c_double),ctypes.c_size_t,ctypes.c_double]
        self.library.rf_register_camera_json.restype=ctypes.c_void_p
        self.library.rf_free.argtypes=[ctypes.c_void_p]
        self.library.rf_free.restype=None

    def reconstruct(self, matches:np.ndarray, threshold:float=.002, glb:Path|None=None, ply:Path|None=None) -> dict[str,Any]:
        rows=np.ascontiguousarray(matches,dtype=np.float64)
        if rows.ndim!=2 or rows.shape[1]!=7:
            raise ValueError("Expected N×7 rows: normalized x1,y1,x2,y2,r,g,b")
        if len(rows)>20000:
            raise ValueError("The bounded native initializer accepts at most 20,000 matches")
        pointer=self.library.rf_reconstruct_json(rows.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(rows),threshold,
                    os.fsencode(glb) if glb else None,os.fsencode(ply) if ply else None)
        if not pointer: raise MemoryError("Native result allocation failed")
        try: result=json.loads(ctypes.string_at(pointer).decode("utf-8"))
        finally: self.library.rf_free(pointer)
        if "error" in result: raise ValueError(result["error"])
        return result

    def register_camera(self, observations:np.ndarray, threshold:float=.003) -> dict[str,Any]:
        """Estimate one additional camera using known world points (XYZ, normalized UV).

        Returns a pose in the existing anchor world frame. Does NOT fuse/triangulate new points.
        """
        rows=np.ascontiguousarray(observations,dtype=np.float64)
        if rows.ndim!=2 or rows.shape[1]!=5 or len(rows)>5000:
            raise ValueError("Expected up to 5000 N×5 [X,Y,Z,u,v] camera observations")
        pointer=self.library.rf_register_camera_json(rows.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(rows),threshold)
        if not pointer: raise MemoryError("Native pose JSON allocation failed")
        try: result=json.loads(ctypes.string_at(pointer).decode("utf-8"))
        finally: self.library.rf_free(pointer)
        if "error" in result:raise ValueError(result["error"])
        return result
