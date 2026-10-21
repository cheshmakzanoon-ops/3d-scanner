"""M08 tested native surface ABI, real photographed stereo and actual triangle GLB/PLY."""
from __future__ import annotations
import json
import struct
import sys
from pathlib import Path
import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from benchmark_surface_middlebury import run as run_real, projected_surface_coverage


def test_uniform_photographs_do_not_generate_false_mesh(tmp_path):
    rgba=np.full((2,44,60,4),128,np.uint8);rgba[...,3]=255
    cams=np.array([[1,0,0,0,1,0,0,0,1,-i*.6,0,0,95,95,29.5,21.5] for i in range(2)],float)
    glb=tmp_path/'unsupported.glb'
    result=NativeEngine().surface(rgba,cams,2.,8.,32,3,glb=glb)
    assert result['dense']['pointCount']==0
    assert 'mesh' not in result
    assert result['meshError']
    assert not glb.exists()


def test_invalid_surface_buffers_rejected_without_native_crash():
    engine=NativeEngine()
    rgba=np.ones((2,44,60,4),np.uint8)
    cams=np.ones((2,16),float)
    with pytest.raises(ValueError):engine.surface(rgba[:1],cams[:1],2.,8.)
    with pytest.raises(ValueError):engine.surface(rgba,cams[:1],2.,8.)
    with pytest.raises(ValueError):engine.surface(rgba,cams,2.,8.,depth_planes=0)


def test_real_photographs_generate_indexed_open_surfaces():
    result=run_real()
    assert result['realPhotographs']
    assert not result['groundTruthUsedForOptimization']
    assert result['meshTriangleCount']>1000
    assert result['meshVertexCount']>400
    # Welding count is an algorithm diagnostic, not a quality/completeness score.
    # Stricter observed-normal/visibility gates may correctly retain separate vertices.
    assert 0 <= result['fusedObservations'] <= result['acceptedSurfaceSamples']
    assert result['acceptedSurfaceSamples']/result['verifiedDepthSamples'] >= .99
    # Motorcycle-specific baseline: 28.48% covered / 27.21% accurate-covered
    # of 343,274 eligible left-image pixels; not whole-object completeness.
    assert result['groundTruthPixelCoverage'] >= .28
    assert result['groundTruthAccuratePixelCoverage5pct'] >= .27
    assert result['groundTruthMatchedMeshVertices']>1000
    assert result['disparityAbsoluteErrorPxP50']<1.0
    assert result['disparityAbsoluteErrorPxP90']<3.0
    assert result['depthRelativeErrorP50']<.1
    assert result['depthRelativeErrorP90']<.05
    assert result['maximumFaceRelativeDepthJump']<.081
    assert not result['photographicUvTextures'] and not result['meshWatertight']


def test_mesh_glb_and_ply_contain_actual_triangle_faces(tmp_path):
    glb=tmp_path/'real-surface.glb';ply=tmp_path/'real-surface.ply'
    metrics=run_real(glb=glb,ply=ply)
    assert glb.stat().st_size>1024 and ply.stat().st_size>1024
    content=glb.read_bytes()
    magic,version,length=struct.unpack_from('<III',content,0)
    assert (magic,version,length)==(0x46546c67,2,len(content))
    json_size,json_type=struct.unpack_from('<II',content,12)
    assert json_type==0x4e4f534a
    gltf=json.loads(content[20:20+json_size])
    primitive=gltf['meshes'][0]['primitives'][0]
    assert primitive['mode']==4, 'Must be indexed TRIANGLES, not GL_POINTS'
    assert 'indices' in primitive
    assert set(primitive['attributes'])=={'POSITION','NORMAL','COLOR_0'}
    assert 'TEXCOORD_0' not in primitive['attributes']
    assert gltf['extras']['photographicUV'] is False
    assert gltf['materials'][0]['doubleSided'] is True
    indices=gltf['accessors'][primitive['indices']]
    assert indices['componentType']==5125 and indices['count']==metrics['meshTriangleCount']*3
    assert gltf['accessors'][0]['count']==metrics['meshVertexCount']
    binary_len,binary_type=struct.unpack_from('<II',content,20+json_size)
    assert binary_type==0x004e4942 and binary_len==gltf['buffers'][0]['byteLength']
    assert binary_len+28+json_size==len(content)
    text=ply.read_text()
    assert f"element vertex {metrics['meshVertexCount']}\n" in text
    assert f"element face {metrics['meshTriangleCount']}\n" in text
    assert 'property list uchar int vertex_indices\n' in text
    assert not glb.read_bytes().startswith(b'ply')


def test_android_mesh_is_shown_and_exportable_without_network_calls():
    root=ROOT/'app/src/main/java/com/realityforge/scanner'
    core=(root/'engine/NativeCore.kt').read_text()
    jni=(ROOT/'reconstruction/src/jni.cpp').read_text()
    pipeline=(root/'engine/SparsePipeline.kt').read_text()
    adapter=(root/'engine/DensePreview.kt').read_text()
    screen=(root/'ui/ScannerApp.kt').read_text()
    repo=(root/'data/ProjectRepository.kt').read_text()
    viewer=(root/'ui/SurfaceMeshViewer.kt').read_text()
    assert 'external fun reconstructSurface(' in core
    assert 'NativeCore_reconstructSurface(' in jni
    assert 'NativeCore.reconstructSurfaceCheckpointed(' in adapter
    assert 'output.put("meshPreview",' in pipeline
    assert 'SurfaceMeshViewer(mesh,' in screen
    assert 'mesh.glb' in screen and 'mesh.ply' in screen
    assert 'mesh.glb' in repo and 'mesh.ply' in repo
    assert 'triangle mesh' in screen.lower() or 'verified triangles' in screen
    assert 'drawPath(path' in viewer


def test_projected_coverage_uses_fixed_pixels_and_frontmost_surface():
    # Analytic synthetic square, all 25 image pixel centers lie on z=2.
    xyz=np.array([[0,0,2],[.8,0,2],[.8,.8,2],[0,.8,2]],float)
    triangles=np.array([[0,1,2],[0,2,3]])
    truth=np.full((5,5),5.,float)  # focal 10 / disparity 5 = depth 2
    result=projected_surface_coverage(xyz,triangles,truth,10.,0.,0.,0.)
    assert result['groundTruthEligiblePixels']==25
    assert result['groundTruthCoveredPixels']==25
    assert result['groundTruthAccurateCoveredPixels5pct']==25
    duplicated=projected_surface_coverage(xyz,np.tile(triangles,(3,1)),truth,10.,0.,0.,0.)
    assert duplicated==result, 'Overlapping duplicate faces must not inflate coverage'
    # A wrong foreground square occludes the correct one; do not choose GT-best depth.
    foreground=np.concatenate([xyz,xyz*.5])
    occluded=projected_surface_coverage(foreground,np.concatenate([triangles,triangles+4]),truth,10.,0.,0.,0.)
    assert occluded['groundTruthCoveredPixels']==25
    assert occluded['groundTruthAccurateCoveredPixels5pct']==0
    truth[0,0]=np.nan;truth[0,1]=0
    masked=projected_surface_coverage(xyz,triangles,truth,10.,0.,0.,0.)
    assert masked['groundTruthEligiblePixels']==23
    assert masked['groundTruthAccurateCoveredPixels5pct']==23


def test_projected_coverage_preserves_holes_and_uses_perspective_depth():
    xyz=np.array([[0,0,2],[.8,0,2],[.8,.8,2],[0,.8,2]],float)
    truth=np.full((5,5),5.,float)
    partial=projected_surface_coverage(xyz,np.array([[0,1,2]]),truth,10.,0.,0.,0.)
    assert partial['groundTruthCoveredPixels']==15  # no invented second triangle
    # The center has inverse depth (.25/1 + .25/2 + .5/4), hence depth 2.
    sloped=np.array([[0,0,1],[.8,0,2],[0,1.6,4]],float)
    only_center=np.zeros((5,5));only_center[2,1]=5.
    result=projected_surface_coverage(sloped,np.array([[0,1,2]]),only_center,10.,0.,0.,0.)
    assert result['groundTruthAccurateCoveredPixels5pct']==1
