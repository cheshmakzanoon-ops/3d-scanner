from pathlib import Path
from tools.native_test_build import native_link_args, native_run_env
import subprocess
import cv2
import numpy as np

def compile_case(root,case,binary):
    sources=[str(root/f'reconstruction/tests/{case}.cpp'),str(root/'reconstruction/src/cancellation.cpp')]
    if case=='texture_visibility_test':sources.append(str(root/'reconstruction/src/core.cpp'))
    subprocess.run(['c++','-std=c++20','-Wall','-Wextra','-Werror','-I',str(root/'reconstruction/include'),*sources,'-lz','-o',str(binary)],check=True)

def test_streaming_png_independent_decode_and_failures(tmp_path):
    root=Path(__file__).resolve().parents[1];binary=tmp_path/'png';out=tmp_path/'atlas.png';reference=tmp_path/'reference.png'
    compile_case(root,'texture_png_test',binary);subprocess.run([str(binary),str(out),str(reference)],check=True)
    actual=cv2.imread(str(out),cv2.IMREAD_UNCHANGED)
    expected=((np.arange(127*89*4,dtype=np.uint64)*37)%256).astype(np.uint8).reshape(89,127,4)
    assert np.array_equal(actual[:,:, [2,1,0,3]],expected)
    assert np.array_equal(cv2.imread(str(reference),cv2.IMREAD_UNCHANGED),actual)
    assert out.stat().st_size < reference.stat().st_size

def test_actual_occlusion_masks_and_missing_depth(tmp_path):
    root=Path(__file__).resolve().parents[1];binary=tmp_path/'visibility'
    compile_case(root,'texture_visibility_test',binary);subprocess.run([str(binary)],check=True)

def test_c_abi_allocation_failure_does_not_escape(tmp_path):
    root=Path(__file__).resolve().parents[1];binary=tmp_path/'abi'
    subprocess.run(['c++','-std=c++20','-I',str(root/'reconstruction/include'),str(root/'reconstruction/tests/agent_057_c_abi_failure.cpp'),*native_link_args(),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],env=native_run_env(),check=True)

def test_compact_camera_input_preserves_original_resampling_rays(monkeypatch, tmp_path):
    import sys
    import types
    import pytest
    root=Path(__file__).resolve().parents[1]
    sys.path.insert(0,str(root/'tools'))
    import benchmark_middlebury as calibration
    import native_api
    image=np.zeros((777,1001,3),np.uint8)
    monkeypatch.setattr(calibration,'load_middlebury',lambda:(image,image,object()))
    for key,value in {'FOCAL_PIXELS':713.7,'CX_LEFT':441.25,
                      'CX_RIGHT':402.75,'CY':319.75}.items():
        monkeypatch.setattr(calibration,key,value)
    captured={}
    class CameraCaptured(Exception):pass
    class Engine:
        def __init__(self):
            self.library=types.SimpleNamespace(
                rf_textured_surface_compact_json=types.SimpleNamespace(),
                rf_textured_surface_json=types.SimpleNamespace(argtypes=[],restype=None))
        def textured_surface(self,low,high,cameras,*args,**kwargs):
            captured.update(low=low,high=high,cameras=cameras.copy())
            raise CameraCaptured
    monkeypatch.setattr(native_api,'NativeEngine',Engine)
    with pytest.raises(CameraCaptured):
        test_compact_real_photographs_and_independent_uv_projection(tmp_path)
    _,height,width,_=captured['low'].shape
    _,photo_height,photo_width,_=captured['high'].shape
    assert (width,height)==(228,177) and (photo_width,photo_height)==(608,472)
    for camera,original_cx in zip(captured['cameras'],(441.25,402.75)):
        fx,fy,cx,cy=camera[12:16]
        for u,v in ((0.,0.),(17.125,41.75),(227.,176.)):
            # Source support is the midpoint between mapped destination pixel edges.
            source_u=((u*1001/width-.5)+((u+1)*1001/width-.5))/2
            source_v=((v*777/height-.5)+((v+1)*777/height-.5))/2
            original_ray=((source_u-original_cx)/713.7,(source_v-319.75)/713.7)
            np.testing.assert_allclose(((u-cx)/fx,(v-cy)/fy),original_ray,atol=1e-14,rtol=0)
            # Direct original calibration must agree with native depth->photo scaling.
            original_photo=np.array([original_ray[0]*713.7*photo_width/1001+(original_cx+.5)*photo_width/1001-.5,
                                     original_ray[1]*713.7*photo_height/777+(319.75+.5)*photo_height/777-.5])
            depth_to_photo=np.array([(u+.5)*photo_width/width-.5,(v+.5)*photo_height/height-.5])
            np.testing.assert_allclose(original_photo,depth_to_photo,atol=1e-12,rtol=0)

def test_compact_real_photographs_and_independent_uv_projection(tmp_path):
    import sys
    import json
    from PIL import Image
    root=Path(__file__).resolve().parents[1]
    sys.path.insert(0,str(root/'tools'))
    from native_api import NativeEngine
    from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY
    from benchmark_texture_middlebury import parse_glb
    left,right,_=load_middlebury()
    original_h,original_w=left.shape[:2]
    width=228;height=round(original_h*width/original_w)
    photo_width=608;photo_height=round(original_h*photo_width/original_w)
    def rgba(im,w,h):
        rgb=cv2.resize(im,(w,h),interpolation=cv2.INTER_AREA)
        return np.concatenate((rgb,np.full((h,w,1),255,np.uint8)),axis=2)
    low=np.stack([rgba(im,width,height) for im in (left,right)])
    high=np.stack([rgba(im,photo_width,photo_height) for im in (left,right)])
    cameras=np.asarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,
        FOCAL_PIXELS*width/original_w,FOCAL_PIXELS*height/original_h,
        (cx+.5)*width/original_w-.5,(CY+.5)*height/original_h-.5] for i,cx in enumerate((CX_LEFT,CX_RIGHT))],float)
    engine=NativeEngine()
    compact=engine.library.rf_textured_surface_compact_json
    compact.argtypes=engine.library.rf_textured_surface_json.argtypes
    compact.restype=engine.library.rf_textured_surface_json.restype
    engine.library.rf_textured_surface_json=compact
    glb=tmp_path/'compact.glb';png=tmp_path/'compact.png'
    result=engine.textured_surface(low,high,cameras,6.,80.,64,3,textured_glb=glb,atlas_png=png)
    assert 'textureError' not in result,result.get('textureError')
    texture=result['texture']
    assert texture['compactCharts'] and texture['supportedTexels']>100
    assert 0<=texture['maximumUvStretch']<=.02+1e-8
    assert texture['textureEngineVersion']=='rf-atlas-v2-groups-zlib-radiometry3'
    assert texture['exposureHeldOutSamples']>0
    assert texture['exposureHeldOutLogErrorAfter']<texture['exposureHeldOutLogErrorBefore']
    assert texture['triangleCount']==texture['inputTriangles']==result['mesh']['triangleCount']
    assert 0 < texture['faceCoverage'] <= 1
    assert texture['chartCount'] <= texture['inputTriangles']
    assert texture['viewSwitchEdges'] is None  # face labels are inapplicable to blended texels
    gltf,buffer=parse_glb(glb);primitive=gltf['meshes'][0]['primitives'][0]
    image_view=gltf['bufferViews'][gltf['images'][0]['bufferView']]
    assert buffer[image_view['byteOffset']:image_view['byteOffset']+image_view['byteLength']]==png.read_bytes()
    image=np.asarray(Image.open(png))
    def attribute(name,dimensions):
        accessor=gltf['accessors'][primitive['attributes'][name]]
        view=gltf['bufferViews'][accessor['bufferView']]
        return np.frombuffer(buffer,dtype='<f4',count=accessor['count']*dimensions,offset=view['byteOffset']).reshape(-1,dimensions)
    positions=attribute('POSITION',3)*np.array([1,-1,-1])
    uvs=attribute('TEXCOORD_0',2)
    def linear(value):
        return np.where(value<=.04045,value/12.92,((value+.055)/1.055)**2.4)
    photo_fx=FOCAL_PIXELS*photo_width/original_w
    photo_fy=FOCAL_PIXELS*photo_height/original_h
    photo_cy=(CY+.5)*photo_height/original_h-.5
    gains=np.asarray(texture['cameraColorGains'])
    verified=0
    for face in np.linspace(0,len(positions)//3-1,100,dtype=int):
        tri_uv=uvs[face*3:face*3+3];tri_xyz=positions[face*3:face*3+3]
        pixel=np.floor(tri_uv.mean(axis=0)*np.array([image.shape[1],image.shape[0]])).astype(int)
        x,y=pixel
        if not(0<=x<image.shape[1] and 0<=y<image.shape[0]) or image[y,x,3]==0:continue
        uv_pixel=(pixel+.5)/np.array([image.shape[1],image.shape[0]])
        bary=np.linalg.solve(np.vstack((tri_uv.T,np.ones(3))),np.r_[uv_pixel,1])
        if bary.min()<.05:continue
        world=bary@tri_xyz
        candidates=[]
        for camera,cx in enumerate((CX_LEFT,CX_RIGHT)):
            photo_cx=(cx+.5)*photo_width/original_w-.5
            px=(world[0]-camera)/world[2]*photo_fx+photo_cx
            py=world[1]/world[2]*photo_fy+photo_cy
            ix,iy=int(np.floor(px)),int(np.floor(py))
            if not(0<=ix<photo_width-1 and 0<=iy<photo_height-1):continue
            fx,fy=px-ix,py-iy;color=np.zeros(3)
            for dy in range(2):
                for dx in range(2):
                    color+=linear(high[camera,iy+dy,ix+dx,:3]/255.)*(fx if dx else 1-fx)*(fy if dy else 1-fy)
            candidates.append(color*gains[camera])
        if not candidates:continue
        observed=linear(image[y,x,:3]/255.);candidates=np.asarray(candidates)
        assert np.all(observed>=candidates.min(axis=0)-.012)
        assert np.all(observed<=candidates.max(axis=0)+.012)
        verified+=1
    assert verified>=12
    receipt={'dataset':'Middlebury Motorcycle: two real rectified photographs; no physical object completeness claim',
             'sourcePixelsVerifiedByIndependentUVProjection':verified,'texture':texture}
    (tmp_path/'compact-receipt.json').write_text(json.dumps(receipt,indent=2))
