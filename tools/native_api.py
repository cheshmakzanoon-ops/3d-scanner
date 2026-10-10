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
        self.library.rf_bundle_adjust_json.argtypes=[ctypes.POINTER(ctypes.c_double),ctypes.c_size_t,
            ctypes.POINTER(ctypes.c_double),ctypes.c_size_t,
            ctypes.POINTER(ctypes.c_double),ctypes.c_size_t,ctypes.c_double,ctypes.c_int]
        self.library.rf_bundle_adjust_json.restype=ctypes.c_void_p
        self.library.rf_dense_json.argtypes=[ctypes.POINTER(ctypes.c_uint8),ctypes.c_int,ctypes.c_int,
            ctypes.POINTER(ctypes.c_double),ctypes.c_size_t,ctypes.c_double,ctypes.c_double,
            ctypes.c_int,ctypes.c_int]
        self.library.rf_dense_json.restype=ctypes.c_void_p
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

    def bundle_adjust(self,cameras:np.ndarray,seeds:np.ndarray,observations:np.ndarray,
                      threshold:float=.003,iterations:int=15) -> dict[str,Any]:
        """Coupled global sparse camera/point BA in the registered anchor coordinate frame.

        Input rows: cameras N x 12, seed points M x 7 (track,XYZ,RGB),
        observations K x 7 (track,camera,u,v,RGB). Does not estimate intrinsics.
        """
        c=np.ascontiguousarray(cameras,dtype=np.float64)
        p=np.ascontiguousarray(seeds,dtype=np.float64)
        o=np.ascontiguousarray(observations,dtype=np.float64)
        if c.ndim!=2 or c.shape[1]!=12 or not 3<=len(c)<=12:
            raise ValueError("Expected 3–12 camera rows N×12")
        if p.ndim!=2 or p.shape[1]!=7 or not 16<=len(p)<=20000:
            raise ValueError("Expected 16–20000 point rows N×7")
        if o.ndim!=2 or o.shape[1]!=7 or not 32<=len(o)<=100000:
            raise ValueError("Expected 32–100000 observation rows N×7")
        f=lambda a: a.ctypes.data_as(ctypes.POINTER(ctypes.c_double))
        ptr=self.library.rf_bundle_adjust_json(f(c),len(c),f(p),len(p),f(o),len(o),threshold,iterations)
        if not ptr:raise MemoryError("Native joint BA allocation failed")
        try: result=json.loads(ctypes.string_at(ptr).decode("utf-8"))
        finally: self.library.rf_free(ptr)
        if "error" in result:raise ValueError(result["error"])
        return result

    def dense(self,rgba:np.ndarray,cameras:np.ndarray,near:float,far:float,
              depth_planes:int=56,pixel_stride:int=3)->dict[str,Any]:
        """Calibrated, rectified CPU multi-view depth. RGBA shape N×H×W×4;
        camera rows N×16: [R(9),t(3),fx,fy,cx,cy]. Returns dense POINTS, not faces.
        """
        images=np.ascontiguousarray(rgba,dtype=np.uint8)
        poses=np.ascontiguousarray(cameras,dtype=np.float64)
        if images.ndim!=4 or images.shape[-1]!=4 or images.shape[0] not in range(2,5):
            raise ValueError('Dense stereo expects 2–4 rectified RGBA images')
        if poses.shape!=(images.shape[0],16):
            raise ValueError('Dense camera rows must be N×16')
        if images.shape[1]>640 or images.shape[2]>640:
            raise ValueError('Dense image dimensions exceed CPU reference cap')
        ptr=self.library.rf_dense_json(images.ctypes.data_as(ctypes.POINTER(ctypes.c_uint8)),
            images.shape[2],images.shape[1],poses.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),
            len(poses),near,far,depth_planes,pixel_stride)
        if not ptr:raise MemoryError('Dense native allocation failed')
        try: result=json.loads(ctypes.string_at(ptr).decode('utf-8'))
        finally:self.library.rf_free(ptr)
        if 'error' in result:raise ValueError(result['error'])
        return result
