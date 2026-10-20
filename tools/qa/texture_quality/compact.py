"""Exercise057's actual opt-in compact C ABI on real stereo; independent metrics."""
import argparse
import ctypes
import hashlib
import json
import os
import subprocess
from pathlib import Path
import sys
import numpy as np
from .assets import load
from .metrics import uv_metrics,geometry_retention
from tools.qa.geometry_quality.__main__ import load as load_geometry


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--native-library',type=Path,required=True);p.add_argument('--candidate-source-sha',required=True);p.add_argument('--output-dir',type=Path,required=True)
    args=p.parse_args();out=args.output_dir;out.mkdir(parents=True,exist_ok=True)
    if len(args.candidate_source_sha)!=40 or any(c not in '0123456789abcdef' for c in args.candidate_source_sha):p.error('exact40hex source SHA required')
    root=Path(__file__).resolve().parents[3];sys.path.insert(0,str(root/'tools'))
    from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY
    from native_api import NativeEngine
    engine=NativeEngine(args.native_library)
    try:call=engine.library.rf_textured_surface_compact_json
    except AttributeError:
        (out/'compact-receipt.json').write_text(json.dumps({'status':'NOT_RUN','reason':'candidate lacks057 compact C ABI','candidate_source_sha':args.candidate_source_sha},indent=2)+'\n');return 2
    call.argtypes=engine.library.rf_textured_surface_json.argtypes;call.restype=ctypes.c_void_p
    import cv2
    left,right,_=load_middlebury();original_h,original_w=left.shape[:2]
    width,photo_width=228,608;height=round(original_h*width/original_w);photo_height=round(original_h*photo_width/original_w)
    def rgba(im,w,h):return np.dstack([cv2.resize(im,(w,h),interpolation=cv2.INTER_AREA),np.full((h,w),255,np.uint8)])
    low=np.ascontiguousarray([rgba(im,width,height) for im in (left,right)],dtype=np.uint8)
    high=np.ascontiguousarray([rgba(im,photo_width,photo_height) for im in (left,right)],dtype=np.uint8)
    rx,ry=width/original_w,height/original_h
    cameras=np.ascontiguousarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,FOCAL_PIXELS*rx,FOCAL_PIXELS*ry,cx*rx,CY*ry] for i,cx in enumerate((CX_LEFT,CX_RIGHT))],dtype=np.float64)
    paths=[out/name for name in ('original.glb','original.ply','compact.glb','compact.png')]
    for path in paths:
        if path.exists():path.unlink()
    raw=call(low.ctypes.data_as(ctypes.POINTER(ctypes.c_uint8)),width,height,
             high.ctypes.data_as(ctypes.POINTER(ctypes.c_uint8)),photo_width,photo_height,
             cameras.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),2,6.,80.,64,3,*[os.fsencode(path) for path in paths])
    if not raw:raise MemoryError('compact native result allocation failed')
    try:native=json.loads(ctypes.string_at(raw).decode())
    finally:engine.library.rf_free(raw)
    (out/'native-result.json').write_text(json.dumps(native,indent=2)+'\n')
    errors=[native[key] for key in ('error','meshError','textureError') if key in native]
    report={'schema_version':1,'status':'FAIL' if errors else 'PASS','native_errors':errors,'candidate_source_sha':args.candidate_source_sha,
            'engine_sha256':hashlib.sha256(args.native_library.read_bytes()).hexdigest(),'data_kind':'real_rectified_stereo_partial',
            'input_sha256':{name:hashlib.sha256(im.tobytes()).hexdigest() for name,im in (('left',left),('right',right))},
            'settings':{'depth_width':width,'photo_width':photo_width,'near':6.,'far':80.,'planes':64,'stride':3,'profile':'057 compact2K CPU'},'held_out_status':'NOT_RUN','physical_gate':'BLOCKED_EXTERNAL'}
    report['source_sha']=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    report['settings_sha256']=hashlib.sha256(json.dumps(report['settings'],sort_keys=True).encode()).hexdigest()
    report['protocol_sha256']=hashlib.sha256((root/'benchmarks/quality_protocols/texture.json').read_bytes()).hexdigest()
    if not errors:
        v,f,uv,tex,asset=load(paths[2],paths[3]);ov,of=load_geometry(paths[0])
        report.update(independent_metrics=uv_metrics(v,f,uv,tex),geometry_retention=geometry_retention(v,f,ov,of),asset=asset,
                      artifact_sha256={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in paths})
        config=json.loads((root/'benchmarks/quality_protocols/texture.json').read_text());metrics=report['independent_metrics'];failures=[]
        for key in ('uv_out_of_bounds_vertices','degenerate_uv_faces','uv_overlap_pairs','distortion_anisotropy_p95','seam_linear_rgb_abs_p95'):
            value=metrics.get(key)
            if value is not None and value>config[key+'_max']:failures.append(key)
        if metrics['supported_face_fraction_original']<config['supported_face_fraction_original_min']:failures.append('face_support')
        report.update(independent_uv_gate='FAIL' if failures else 'PASS_LIMITED_UV',independent_uv_failures=failures)
    (out/'compact-receipt.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));return int(bool(errors))


if __name__=='__main__':raise SystemExit(main())
