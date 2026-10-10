"""Actual JNI kernel's host ABI and independent real-photo depth measurements."""
from pathlib import Path
import sys
import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from benchmark_dense_middlebury import run


def test_low_texture_view_must_not_produce_fake_geometry():
    rgba=np.ones((2,40,48,4),dtype=np.uint8)*128
    rgba[...,3]=255
    camera=[]
    for i in range(2):
        camera.append([1,0,0,0,1,0,0,0,1,-i*.5,0,0,70,70,23.5,19.5])
    result=NativeEngine().dense(rgba,np.asarray(camera),2.,8.,32,3)
    assert result['representation']=='dense_points'
    assert result['pointCount']==0
    assert result['triangleCount']==0
    assert not result['textured']


def test_malformed_rectified_camera_shapes_rejected():
    rgba=np.ones((2,40,48,4),dtype=np.uint8)*255
    with pytest.raises(ValueError):
        NativeEngine().dense(rgba,np.ones((2,12)),2,8)
    with pytest.raises(ValueError):
        NativeEngine().dense(rgba[:1],np.ones((1,16)),2,8)


def test_real_stereo_depth_vs_independent_middlebury_ground_truth():
    measured=run()
    assert measured['realPhotographs']
    assert measured['groundTruthUsedForOptimization'] is False
    assert measured['cameraCount']==2
    assert measured['groundTruthMatchedPoints']>=1000
    assert measured['densePointCount']>=1500
    assert measured['disparityAbsoluteErrorPxP50']<2.0
    assert measured['disparityAbsoluteErrorPxP90']<10.0
    assert measured['depthRelativeErrorP50']<.08
    assert measured['depthRelativeErrorP90']<.30


def test_dense_jni_wiring_and_explicit_limit_contract():
    bridge=(ROOT/'reconstruction/src/jni.cpp').read_text()
    app=(ROOT/'app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt').read_text()
    core=(ROOT/'reconstruction/src/dense.cpp').read_text()
    assert 'Java_com_realityforge_scanner_engine_NativeCore_reconstructDense' in bridge
    assert 'NativeCore.reconstructDense' in app
    assert 'depthConsistencyFraction' in core
    assert 'sampledPixels' in core
    assert 'triangleCount' in core
