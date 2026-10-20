"""M14 exercises eight real input buffers; rendered fixtures are explicitly synthetic."""
from pathlib import Path
import sys
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from benchmark_multiview_coverage import fixture, run


def test_more_views_increase_observed_extent_on_fixed_reference_grid():
    report=run()
    assert report['synthetic'] and not report['physicalDevice']
    assert report['groundTruthUsedByDepthSolver'] is False
    assert report['coverageRatio']>1.35
    full=report['measurements']['8']
    assert full['depthErrorP50']<.08
    assert all(n>0 for n in full['consistentPixelsPerView'])
    assert full['peakLuminanceBytes']<=4*104*76*4
    for i,sources in enumerate(full['sourceViewIndices']):
        assert 1<=len(sources)<=3 and len(set(sources))==len(sources) and i not in sources


def test_eight_camera_texture_atlas_has_valid_source_provenance(tmp_path):
    images,cameras=fixture()
    glb=tmp_path/'multi.glb';png=tmp_path/'multi.png'
    result=NativeEngine().textured_surface(images,images,cameras,3.1,7,64,2,
        textured_glb=glb,atlas_png=png)
    assert 'meshError' not in result and 'textureError' not in result, result.keys()
    texture=result['texture']
    assert len(texture['cameraTriangleCounts'])==8
    assert texture['faceCoverage']>.9
    assert texture['triangleCount']>100
    assert len(texture['cameraColorGains'])==8
    assert glb.read_bytes()[:4]==b'glTF'
    from PIL import Image
    with Image.open(png) as atlas:
        atlas.load()
        assert atlas.size==(texture['atlasWidth'],texture['atlasHeight'])
        assert atlas.width*atlas.height<=4_500_000
        assert atlas.getpixel((2*(104+6)+3,2*(76+6)+3))[3]==0 # unused ninth tile


def test_fully_masked_eighth_view_cannot_create_geometry():
    images,cameras=fixture()
    images[7,:,:,3]=0
    result=NativeEngine().dense(images,cameras,3.1,7,40,3)
    assert len(result['consistentPixelsPerView'])==8
    assert result['consistentPixelsPerView'][7]==0
    assert result['pointCount']>100


def test_ninth_view_and_excessive_sampling_workload_rejected():
    images,cameras=fixture()
    with pytest.raises(ValueError):
        NativeEngine().dense(np.concatenate([images,images[:1]]),np.vstack([cameras,cameras[:1]]),3.1,7)
    full=np.zeros((8,384,384,4),np.uint8);full[:,:,:,3]=255
    with pytest.raises(ValueError,match='sampling budget'):
        NativeEngine().dense(full,cameras,3.1,7,32,2)


def test_small_set_reference_plan_retains_all_sources():
    images,cameras=fixture(4)
    result=NativeEngine().dense(images,cameras,3.1,7,40,3)
    assert result['sourceViewIndices']==[[j for j in range(4) if j!=i] for i in range(4)]
