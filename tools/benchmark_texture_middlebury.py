#!/usr/bin/env python3
"""M09 real-photo UV benchmark. Ground truth is not used for reconstruction.

This validates actual indexed glTF TEXCOORD_0 and an embedded photo PNG for two
published calibrated Motorcycle images. Not a complete multi-side object scan.
"""
from __future__ import annotations
import argparse
import io
import json
import struct
from pathlib import Path
import cv2
import numpy as np
from benchmark_middlebury import load_middlebury, FOCAL_PIXELS, CX_LEFT, CX_RIGHT, CY
from native_api import NativeEngine


def parse_glb(file:Path):
    body=file.read_bytes()
    signature,version,length=struct.unpack_from('<III',body)
    assert signature==0x46546c67 and version==2 and length==len(body)
    js_size,js_tag=struct.unpack_from('<II',body,12)
    assert js_tag==0x4e4f534a
    gltf=json.loads(body[20:20+js_size])
    binary_size,binary_tag=struct.unpack_from('<II',body,20+js_size)
    assert binary_tag==0x004e4942
    binary=body[28+js_size:]
    assert len(binary)==binary_size==gltf['buffers'][0]['byteLength']
    return gltf,binary


def run(output_dir:Path|None=None,width:int=228, photo_width:int=608) -> dict:
    left,right,truth=load_middlebury()
    original_h,original_w=left.shape[:2]
    height=round(original_h*width/original_w)
    photo_height=round(original_h*photo_width/original_w)
    def rgba(image,w,h):
        rgb=cv2.resize(image,(w,h),interpolation=cv2.INTER_AREA)
        return np.concatenate((rgb,np.full((h,w,1),255,np.uint8)),axis=2)
    low=np.stack([rgba(im,width,height) for im in (left,right)])
    high=np.stack([rgba(im,photo_width,photo_height) for im in (left,right)])
    sx=width/original_w;sy=height/original_h
    # Match cv2.resize pixel centres; rounded height uses its separate scale.
    cameras=np.asarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,
        FOCAL_PIXELS*sx,FOCAL_PIXELS*sy,(cx+.5)*sx-.5,(CY+.5)*sy-.5]
        for i,cx in enumerate((CX_LEFT,CX_RIGHT))],dtype=np.float64)
    root=output_dir or Path('build/host')
    root.mkdir(parents=True,exist_ok=True)
    glb=root/'middlebury-photographic-texture.glb'
    png=root/'middlebury-photographic-atlas.png'
    output=NativeEngine().textured_surface(low,high,cameras,6.,80.,64,3,
        textured_glb=glb,atlas_png=png)
    assert 'meshError' not in output,output.get('meshError')
    assert 'textureError' not in output,output.get('textureError')
    texture=output['texture'];gltf,binary=parse_glb(glb)
    prim=gltf['meshes'][0]['primitives'][0]
    assert prim['mode']==4 and 'TEXCOORD_0' in prim['attributes']
    assert 'COLOR_0' not in prim['attributes'] and gltf['materials'][0]['pbrMetallicRoughness']['baseColorTexture']['index']==0
    image_view=gltf['bufferViews'][gltf['images'][0]['bufferView']]
    embedded=binary[image_view['byteOffset']:image_view['byteOffset']+image_view['byteLength']]
    assert embedded==png.read_bytes(), 'PNG must be embedded without substitution'
    from PIL import Image
    photographic=Image.open(io.BytesIO(embedded));photographic.load()
    assert photographic.size==(texture['atlasWidth'],texture['atlasHeight'])
    # Pixel provenance check: atlas interiors equal measured exposure transforms, not
    # vertex-color procedural fills. The two camera tiles are side-by-side.
    gutter=3
    def corrected(pixel, gains):
        rgb=np.array(pixel[:3],dtype=float)/255
        linear=np.where(rgb<=.04045,rgb/12.92,((rgb+.055)/1.055)**2.4)
        c=np.clip(linear*np.array(gains),0,1)
        srgb=np.where(c<=.0031308,12.92*c,1.055*c**(1/2.4)-.055)
        return tuple(np.floor(srgb*255+.5).astype(int))+(int(pixel[3]),)
    for camera in (0,1):
        for x,y in ((22,21),(photo_width//3,photo_height//2),(photo_width-22,photo_height-21)):
            assert photographic.getpixel((camera*(photo_width+2*gutter)+gutter+x,gutter+y))==corrected(high[camera,y,x],texture['cameraColorGains'][camera])
    # independently check UV accessor values and bounds.
    uv_acc=gltf['accessors'][prim['attributes']['TEXCOORD_0']]
    view=gltf['bufferViews'][uv_acc['bufferView']]
    uv=np.frombuffer(binary,dtype='<f4',count=uv_acc['count']*2,offset=view['byteOffset']).reshape(-1,2)
    assert uv_acc['type']=='VEC2' and np.isfinite(uv).all() and ((uv>=0)&(uv<=1)).all()
    assert texture['triangleCount']>100 and texture['faceCoverage']>.10
    # Existing geometric truth remains independently measurable.
    mesh=output['mesh']
    points=np.asarray(mesh['vertices'],dtype=float)
    zs=points[:,2]
    px=np.rint(points[:,0]*FOCAL_PIXELS/zs+CX_LEFT).astype(int)
    py=np.rint(points[:,1]*FOCAL_PIXELS/zs+CY).astype(int)
    valid=(px>=0)&(py>=0)&(px<original_w)&(py<original_h)&(zs>0)
    px=np.clip(px,0,original_w-1);py=np.clip(py,0,original_h-1)
    observed=truth[py,px].astype(float)
    valid &= np.isfinite(observed)&(observed>0)
    disp=FOCAL_PIXELS/zs-(CX_RIGHT-CX_LEFT)
    errors=np.abs(disp[valid]-observed[valid])
    return {
        'schema':10,'dataset':'Middlebury Motorcycle 2014, two real calibrated photographs',
        'groundTruthUsedForTextureOrOptimization':False,'realPhotographs':True,
        'denseDimensions':[width,height],'sourcePhotoDimensions':[photo_width,photo_height],
        'atlasedDimensions':[texture['atlasWidth'],texture['atlasHeight']],
        'photographicPixelsVerified':6,'textureEmbeddedPng':True,'gltfUvAccessorValidated':True,
        'uvVertices':texture['uvVertexCount'],'texturedTriangles':texture['triangleCount'],
        'candidateMeshTriangles':texture['inputTriangles'],'faceCoverage':texture['faceCoverage'],
        'cameraTriangleCounts':texture['cameraTriangleCounts'],'potentialViewSeamEdges':texture['viewSwitchEdges'],
        'uvOrigin':texture['uvOrigin'],'exposureSamples':texture['exposureSamples'],
        'cameraColorGains':texture['cameraColorGains'],
        'overlapMedianLogErrorBefore':texture['overlapMedianLogErrorBefore'],
        'overlapMedianLogErrorAfter':texture['overlapMedianLogErrorAfter'],
        'potentialViewSeamEdgesBefore':texture['viewSwitchEdgesBefore'],
        'reassignedFaces':texture['reassignedFaces'],
        'meshVertexCount':mesh['vertexCount'],'medianDisparityErrorPx':float(np.median(errors)),
        'limits':['2 real stereo photos only, no physical-device 360 capture',
                  'Camera-tiled UV atlas; not seamless multi-view material blending',
                  'Open partial triangle mesh in arbitrary scale, no global completeness'],
    }


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output',type=Path,default=Path('reports/m10-middlebury-texture.json'))
    ap.add_argument('--gate',action='store_true')
    args=ap.parse_args()
    result=run()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    if args.gate:
        assert result['texturedTriangles']>=100
        assert result['faceCoverage']>=.95
        assert result['uvOrigin']=='upper_left'
        assert result['potentialViewSeamEdges']<=result['potentialViewSeamEdgesBefore']
        assert result['overlapMedianLogErrorAfter']<=result['overlapMedianLogErrorBefore']
        assert result['medianDisparityErrorPx']<5
        assert result['textureEmbeddedPng'] and result['gltfUvAccessorValidated']
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
