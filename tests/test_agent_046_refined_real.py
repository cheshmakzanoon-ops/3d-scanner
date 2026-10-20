"""Real rectified stereo qualification; truth is evaluation-only, not solver input."""
from pathlib import Path
import ctypes,hashlib,json,sys,time
import cv2
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY

def test_refined_pyramid_real_stereo_retains_declared_accuracy_and_support():
    left,right,disparity=load_middlebury()
    h,w=left.shape[:2];outw=228;outh=round(h*outw/w)
    rgba=np.ascontiguousarray([np.concatenate([cv2.resize(image,(outw,outh),interpolation=cv2.INTER_AREA),np.full((outh,outw,1),255,np.uint8)],axis=-1) for image in (left,right)])
    cameras=np.ascontiguousarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,FOCAL_PIXELS*outw/w,FOCAL_PIXELS*outh/h,cx*outw/w,CY*outh/h] for i,cx in enumerate((CX_LEFT,CX_RIGHT))],dtype=np.float64)
    cw=outw//2;ch=outh//2
    coarse=np.ascontiguousarray([cv2.resize(image,(cw,ch),interpolation=cv2.INTER_AREA) for image in rgba])
    coarse[...,3]=255
    coarse_cameras=cameras.copy();sx=cw/outw;sy=ch/outh
    coarse_cameras[:,12]*=sx;coarse_cameras[:,13]*=sy
    coarse_cameras[:,14]=(coarse_cameras[:,14]+.5)*sx-.5
    coarse_cameras[:,15]=(coarse_cameras[:,15]+.5)*sy-.5
    identity=hashlib.sha256(rgba.tobytes()+cameras.tobytes()+coarse.tobytes()+coarse_cameras.tobytes()+b'cpu-refined-v1|near6|far80|planes64|stride3|local6').hexdigest().encode()
    engine=NativeEngine();start=time.monotonic();baseline=engine.dense(rgba,cameras,6,80,64,3);baseline_seconds=time.monotonic()-start
    call=engine.library.rf_dense_refined_json
    u8=ctypes.POINTER(ctypes.c_uint8);f64=ctypes.POINTER(ctypes.c_double)
    call.argtypes=[u8,ctypes.c_int,ctypes.c_int,f64,ctypes.c_size_t,ctypes.c_double,ctypes.c_double,ctypes.c_int,ctypes.c_int,f64,ctypes.c_size_t,u8,ctypes.c_int,ctypes.c_int,f64,ctypes.c_char_p]
    call.restype=ctypes.c_void_p
    start=time.monotonic();pointer=call(rgba.ctypes.data_as(u8),outw,outh,cameras.ctypes.data_as(f64),2,6,80,64,3,None,0,coarse.ctypes.data_as(u8),cw,ch,coarse_cameras.ctypes.data_as(f64),identity)
    assert pointer
    try:refined=json.loads(ctypes.string_at(pointer))
    finally:engine.library.rf_free(pointer)
    refined_seconds=time.monotonic()-start
    assert 'error' not in refined,refined
    def evaluate(result):
        points=np.asarray(result['points'],float).reshape(-1,8)
        x=np.rint(points[:,0]*FOCAL_PIXELS/points[:,2]+CX_LEFT).astype(int)
        y=np.rint(points[:,1]*FOCAL_PIXELS/points[:,2]+CY).astype(int)
        inside=(x>=0)&(x<w)&(y>=0)&(y<h);truth=disparity[np.clip(y,0,h-1),np.clip(x,0,w-1)]
        valid=inside&np.isfinite(truth)&(truth>0)&(truth+CX_RIGHT-CX_LEFT>0)
        error=np.abs(FOCAL_PIXELS/points[valid,2]-(CX_RIGHT-CX_LEFT)-truth[valid])
        depth=FOCAL_PIXELS/(truth[valid]+CX_RIGHT-CX_LEFT)
        relative=np.abs(points[valid,2]-depth)/depth
        return {'points':len(points),'truth_supported':int(valid.sum()),'disparity_p50':float(np.median(error)),'disparity_p90':float(np.percentile(error,90)),'relative_depth_p50':float(np.median(relative)),'relative_depth_p90':float(np.percentile(relative,90))}
    old=evaluate(baseline);new=evaluate(refined)
    report={'dataset':'Middlebury2014 Motorcycle, real rectified stereo','truth_used_by_solver':False,'source_calibration':'published benchmark intrinsics, arbitrary unit baseline; known poses','input_sha256':identity.decode(),'physical_device':False,'baseline':old,'refined':new,'baseline_seconds':baseline_seconds,'refined_seconds':refined_seconds,'coarse_depth_maps':refined['coarseDepthMaps'],'propagated_hypotheses':refined['propagatedHypotheses'],'admitted_working_set_bytes':refined['admittedWorkingSetBytes'],'declared_tradeoff':'retain >=90% truth-supported points; error p50 <=1.25*baseline+0.15px; existing strict absolute stereo gates retained; wall times observational under shared cloud CPU'}
    output=ROOT/'build/agents/046-refined-middlebury.json';output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(report,indent=2)+'\n')
    assert refined['coarseDepthMaps']==2 and refined['propagatedHypotheses']>0
    assert new['truth_supported']>=max(1000,.9*old['truth_supported'])
    assert new['points']>=1500 and new['disparity_p50']<2 and new['disparity_p90']<10
    assert new['relative_depth_p50']<.08 and new['relative_depth_p90']<.30
    assert new['disparity_p50']<=1.25*old['disparity_p50']+.15
