"""Real rectified stereo qualification; truth is evaluation-only, not solver input."""
from pathlib import Path
import ctypes,hashlib,json,struct,subprocess,sys,time
import cv2
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY

def _resized_intrinsics(fx,fy,cx,cy,sx,sy):
    # OpenCV resize maps destination pixel centers back through (u+.5)/s-.5.
    return fx*sx,fy*sy,(cx+.5)*sx-.5,(cy+.5)*sy-.5

def test_refined_fixture_resize_preserves_asymmetric_calibrated_rays():
    fx,fy,cx,cy=731.,903.,287.25,199.75
    sx,sy=.37,.61
    ray=np.array([.21,-.17])
    source_pixel=np.array([fx*ray[0]+cx,fy*ray[1]+cy])
    destination_pixel=(source_pixel+.5)*np.array([sx,sy])-.5
    rfx,rfy,rcx,rcy=_resized_intrinsics(fx,fy,cx,cy,sx,sy)
    reconstructed=(destination_pixel-np.array([rcx,rcy]))/np.array([rfx,rfy])
    np.testing.assert_allclose(reconstructed,ray,rtol=0,atol=1e-15)
    unadjusted=(destination_pixel-np.array([cx*sx,cy*sy]))/np.array([rfx,rfy])
    assert np.max(np.abs(unadjusted-ray))>1e-4

def test_refined_pyramid_real_stereo_retains_declared_accuracy_and_support(tmp_path):
    left,right,disparity=load_middlebury()
    h,w=left.shape[:2];outw=228;outh=round(h*outw/w)
    rgba=np.ascontiguousarray([np.concatenate([cv2.resize(image,(outw,outh),interpolation=cv2.INTER_AREA),np.full((outh,outw,1),255,np.uint8)],axis=-1) for image in (left,right)])
    cameras=np.ascontiguousarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,*_resized_intrinsics(FOCAL_PIXELS,FOCAL_PIXELS,cx,CY,outw/w,outh/h)] for i,cx in enumerate((CX_LEFT,CX_RIGHT))],dtype=np.float64)
    cw=outw//2;ch=outh//2
    coarse=np.ascontiguousarray([cv2.resize(image,(cw,ch),interpolation=cv2.INTER_AREA) for image in rgba])
    coarse[...,3]=255
    coarse_cameras=cameras.copy();sx=cw/outw;sy=ch/outh
    coarse_cameras[:,12]*=sx;coarse_cameras[:,13]*=sy
    coarse_cameras[:,14]=(coarse_cameras[:,14]+.5)*sx-.5
    coarse_cameras[:,15]=(coarse_cameras[:,15]+.5)*sy-.5
    identity=hashlib.sha256(rgba.tobytes()+cameras.tobytes()+coarse.tobytes()+coarse_cameras.tobytes()+b'cpu-refined-v3|near6|far80|planes64|stride3|local6|strict1|normal3|normalConsistency1|angle35').hexdigest().encode()
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
    # Driver accepts only real photos/calibration: no disparity/truth buffer exists.
    fixture=tmp_path/'photographs.bin'
    fixture.write_bytes(struct.pack('<5I',outw,outh,2,cw,ch)+cameras.tobytes()+rgba.tobytes()+coarse_cameras.tobytes()+coarse.tobytes())
    executable=tmp_path/'real-profile'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Werror','-I'+str(ROOT/'reconstruction/include'),str(ROOT/'reconstruction/tests/agent_046_real_profile.cpp'),'-L'+str(ROOT/'build/host'),'-lrealityforge','-Wl,-rpath,'+str(ROOT/'build/host'),'-o',str(executable)],check=True)
    def profile(name):
        return json.loads(subprocess.check_output([str(executable),str(fixture),name,hashlib.sha256(fixture.read_bytes()).hexdigest()],timeout=120))
    nonstrict=profile('refined');strict=profile('strict')
    old=evaluate(baseline);new=evaluate(refined);legacy=evaluate(nonstrict);conservative=evaluate(strict)
    report={'dataset':'Middlebury2014 Motorcycle, real rectified stereo','truth_used_by_solver':False,'source_calibration':'published benchmark intrinsics, arbitrary unit baseline; known poses','pixel_center_mapping':'OpenCV resize: (pixel+.5)*scale-.5 on initial and coarse levels','input_sha256':identity.decode(),'profile':'cpu-refined-v3|local6|strict1|normal3|normalConsistency1|angle35|robust0','physical_device':False,'baseline':old,'refined_without_strict':legacy,'strict_without_refinement':conservative,'refined':new,'baseline_seconds':baseline_seconds,'refined_seconds':refined_seconds,'coarse_depth_maps':refined['coarseDepthMaps'],'propagated_hypotheses':refined['propagatedHypotheses'],'normal_hypotheses_evaluated':refined['normalHypothesesEvaluated'],'normal_refined_pixels':refined['normalRefinedPixels'],'normal_support_pairs':refined['normalSupportPairs'],'normal_rejected_pairs':refined['normalRejectedPairs'],'normal_unknown_pairs':refined['normalUnknownPairs'],'admitted_working_set_bytes':refined['admittedWorkingSetBytes'],'declared_tradeoff':'Non-strict refinement retains >=90% truth-supported points and p50<=1.25*baseline+0.15px. Conservative strict profiles retain >=60% and >=1000 truth points; p50<=1.10*baseline+0.05px,p90<=0.95*baseline+0.10px. All profiles retain absolute stereo gates. Gates declared before strict/normal admission; wall times observational under shared cloud CPU.'}
    output=ROOT/'build/agents/046-refined-middlebury.json';output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(report,indent=2)+'\n')
    assert refined['coarseDepthMaps']==2 and refined['propagatedHypotheses']>0
    assert refined['normalHypothesesEvaluated']>0 and refined['normalRefinedPixels']>0 and refined['normalSupportPairs']>0
    assert legacy['truth_supported']>=max(1000,.9*old['truth_supported'])
    assert legacy['disparity_p50']<=1.25*old['disparity_p50']+.15
    for metrics in (legacy,conservative,new):
        assert metrics['points']>=1500 and metrics['disparity_p50']<2 and metrics['disparity_p90']<10
        assert metrics['relative_depth_p50']<.08 and metrics['relative_depth_p90']<.30
    for metrics in (conservative,new):
        assert metrics['truth_supported']>=max(1000,.6*old['truth_supported'])
        assert metrics['disparity_p50']<=1.10*old['disparity_p50']+.05
        assert metrics['disparity_p90']<=.95*old['disparity_p90']+.10
