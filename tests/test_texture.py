"""M09 actual source-photograph pixels in UV GLB; no fabricated image atlas."""
from __future__ import annotations
import io
import json
import struct
import sys
from pathlib import Path
import numpy as np
import pytest
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from benchmark_texture_middlebury import parse_glb,run as real_texture
from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY


def simple_scene():
    w,h=64,48
    cameras=np.array([[1,0,0,0,1,0,0,0,1,-.6*i,0,0,100,100,(w-1)/2,(h-1)/2]
                      for i in range(2)],float)
    imgs=np.full((2,h,w,4),128,np.uint8)
    imgs[...,3]=255
    return imgs,cameras


def test_uniform_photos_do_not_invent_textures(tmp_path):
    rgba,cams=simple_scene()
    png=tmp_path/'unsupported.png';glb=tmp_path/'unsupported.glb'
    out=NativeEngine().textured_surface(rgba,rgba,cams,2,9,32,3,
                                        textured_glb=glb,atlas_png=png)
    assert 'mesh' not in out and 'meshError' in out
    assert 'texture' not in out
    assert not png.exists() and not glb.exists()


def test_invalid_shapes_rejected_before_read():
    rgba,cams=simple_scene()
    with pytest.raises(ValueError):NativeEngine().textured_surface(rgba,rgba[:1],cams,2,8)
    with pytest.raises(ValueError):NativeEngine().textured_surface(rgba,rgba,cams[:1],2,8)
    with pytest.raises(ValueError):NativeEngine().textured_surface(rgba,np.ones((2,1120,100,4),np.uint8),cams,2,8)
    with pytest.raises(ValueError):NativeEngine().textured_surface(rgba,rgba,cams,2,8,depth_planes=0)


def test_real_image_texture_is_embedded_losslessly(tmp_path):
    result=real_texture(tmp_path)
    assert result['realPhotographs'] and not result['groundTruthUsedForTextureOrOptimization']
    assert result['texturedTriangles']>100
    assert result['gltfUvAccessorValidated'] and result['textureEmbeddedPng']
    assert result['photographicPixelsVerified']>=6
    assert result['faceCoverage']>.95
    assert result['uvOrigin']=='upper_left'
    assert result['exposureSamples']>=32
    assert result['overlapMedianLogErrorAfter']<result['overlapMedianLogErrorBefore']
    assert result['potentialViewSeamEdges']<=result['potentialViewSeamEdgesBefore']
    assert result['medianDisparityErrorPx']<2
    gltf,buffer=parse_glb(tmp_path/'middlebury-photographic-texture.glb')
    primitive=gltf['meshes'][0]['primitives'][0]
    assert primitive['mode']==4
    assert set(primitive['attributes'])=={'POSITION','NORMAL','TEXCOORD_0'}
    assert gltf['materials'][0]['pbrMetallicRoughness']['baseColorTexture']=={'index':0}
    assert gltf['images'][0]['mimeType']=='image/png'
    assert gltf['extras']['photographicUV'] is True
    assert gltf['extras']['watertight'] is False
    assert 'COLOR_0' not in primitive['attributes']
    imgView=gltf['bufferViews'][gltf['images'][0]['bufferView']]
    texture=buffer[imgView['byteOffset']:imgView['byteOffset']+imgView['byteLength']]
    assert texture== (tmp_path/'middlebury-photographic-atlas.png').read_bytes()
    atlas=Image.open(io.BytesIO(texture));atlas.load()
    assert atlas.mode=='RGBA' and atlas.size==tuple(result['atlasedDimensions'])


def test_actual_uv_geometry_corresponds_to_photo_projection(tmp_path):
    real_texture(tmp_path)
    gltf,buf=parse_glb(tmp_path/'middlebury-photographic-texture.glb')
    primitive=gltf['meshes'][0]['primitives'][0]
    def values(key,dim):
        a=gltf['accessors'][primitive['attributes'][key]]
        v=gltf['bufferViews'][a['bufferView']]
        return np.frombuffer(buf,dtype='<f4',count=a['count']*dim,offset=v['byteOffset']).reshape(-1,dim)
    xyz=values('POSITION',3);uv=values('TEXCOORD_0',2)
    assert len(xyz)==len(uv)>50
    left,right,_=load_middlebury();h,w=left.shape[:2]
    tex_w,tex_h=608,round(h*608/w)
    width,height=(2*(tex_w+6)),tex_h+6
    for k in np.linspace(0,len(xyz)-1,25,dtype=int):
        # glTF uses Y and Z axes inverted relative to native camera coordinates.
        x,y,z=xyz[k];p=np.array([x,-y,-z])
        atlas_x=float(uv[k,0])*width-.5
        atlas_y=float(uv[k,1])*height-.5
        camera=0 if atlas_x<width/2 else 1
        local_x=atlas_x-(camera*(tex_w+6)+3)
        local_y=atlas_y-3
        # Published original rectified intrinsics and unit baseline. Resize
        # directly to photographic pixel centres, including rounded-height scale.
        c=FOCAL_PIXELS*tex_w/w
        cx=([CX_LEFT,CX_RIGHT][camera]+.5)*tex_w/w-.5
        cy=(CY+.5)*tex_h/h-.5
        expected_x=(p[0]-camera)/p[2]*c+cx
        expected_y=p[1]/p[2]*(FOCAL_PIXELS*tex_h/h)+cy
        assert abs(local_x-expected_x)<0.05
        assert abs(local_y-expected_y)<0.05


def test_android_source_has_fallback_for_photo_preprocess_failure():
    src=(ROOT/'app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt').read_text()
    assert 'textureInputFailure' in src
    assert 'NativeCore.reconstructSurfaceCheckpointed(' in src
    assert 'NativeCore.reconstructTexturedSurfaceCheckpointed(' in src
    assert 'texturedGlb.delete();atlasPng.delete()' in src
