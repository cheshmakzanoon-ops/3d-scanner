"""M02 camera registration tests exercise the compiled production C++ PnP core."""
from __future__ import annotations
import json
from pathlib import Path
import sys
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from three_view import run_three

@pytest.fixture(scope='module')
def engine():return NativeEngine()

def synthetic(n=170,noise=0.,outliers=0,seed=17,planar=False):
    rng=np.random.default_rng(seed)
    pts=np.column_stack([rng.uniform(-1,1,(n,2)),rng.uniform(3,8,n)])
    if planar:pts[:,2]=5
    a=.16
    r=np.array([[np.cos(a),0,np.sin(a)],[0,1,0],[-np.sin(a),0,np.cos(a)]])
    t=np.array([-1.25,.11,.17])
    camera=pts@r.T+t
    uv=camera[:,:2]/camera[:,2,None]+rng.normal(0,noise,(n,2))
    uv[:outliers]=rng.uniform(-.8,.8,(outliers,2))
    return np.column_stack((pts,uv)),r,t

@pytest.mark.parametrize('seed',[1,17,81,170])
def test_pnp_recovers_camera_with_noise_and_outliers(engine,seed):
    tracks,r,t=synthetic(noise=.0002,outliers=40,seed=seed)
    result=engine.register_camera(tracks,threshold=.003)
    assert result['inlierCount']>=120
    assert result['inputTracks']==len(tracks)
    assert result['medianErrorNormalized']<.001
    assert np.linalg.norm(np.asarray(result['rotation']).reshape(3,3)-r)<.015
    assert np.linalg.norm(np.asarray(result['translation'])-t)<.04
    assert all(0<=i<len(tracks) for i in result['inlierIndices'])

def test_pnp_deterministic(engine):
    tracks,*_=synthetic(noise=.0001,outliers=20)
    assert engine.register_camera(tracks)==engine.register_camera(tracks)

@pytest.mark.parametrize('count',[0,1,8,15])
def test_pnp_rejects_insufficient_tracks(engine,count):
    tracks,*_=synthetic(n=max(count,1))
    with pytest.raises(ValueError):engine.register_camera(tracks[:count])

@pytest.mark.parametrize('bad',[np.nan,np.inf,-np.inf])
def test_pnp_rejects_nonfinite(engine,bad):
    tracks,*_=synthetic();tracks[0,3]=bad
    with pytest.raises(ValueError,match='PnP coordinate'):engine.register_camera(tracks)

def test_pnp_planar_geometry_is_verified_or_explicitly_unsupported(engine):
    # The legacy full-rank DLT explicitly rejects planes. A calibrated planar
    # implementation may accept one only with independently checked rigid pose.
    tracks, rotation, translation = synthetic(planar=True)
    try:
        result = engine.register_camera(tracks)
    except ValueError as failure:
        assert "consistent" in str(failure) or "geometry" in str(failure)
        return
    recovered = np.asarray(result['rotation']).reshape(3, 3)
    np.testing.assert_allclose(recovered, rotation, atol=1e-5, rtol=0)
    np.testing.assert_allclose(result['translation'], translation, atol=1e-5, rtol=0)
    np.testing.assert_allclose(recovered @ recovered.T, np.eye(3), atol=1e-8, rtol=0)
    assert np.linalg.det(recovered) > .999999
    assert result['inlierCount'] == len(tracks)
    assert result['medianErrorNormalized'] < 1e-8

def test_pnp_rejects_bad_threshold(engine):
    tracks,*_=synthetic()
    with pytest.raises(ValueError):engine.register_camera(tracks,threshold=0.)

@pytest.fixture(scope='module')
def three_camera():
    return run_three(ROOT/'benchmarks/fixtures/relief')

def test_actual_three_photographs_register_third_camera(three_camera):
    r=three_camera
    assert r['synthetic'] is True
    assert r['registeredCameras']==3
    assert r['trackCandidates']>=100
    assert r['thirdCamera']['inlierCount']>=90
    assert r['thirdCamera']['medianErrorNormalized']<.001

def test_third_photo_ground_truth(three_camera):
    cameras=json.loads((ROOT/'benchmarks/fixtures/relief/calibration.json').read_text())['cameras']
    anchor_scale=np.linalg.norm(cameras[1]['t'])
    expected_rotation=np.array(cameras[2]['R'])
    estimated_rotation=np.array(three_camera['thirdCamera']['rotation']).reshape(3,3)
    rotation_error=np.degrees(np.arccos(np.clip((np.trace(estimated_rotation@expected_rotation.T)-1)/2,-1,1)))
    assert rotation_error<1.
    # PnP output lives in the initial *unit-baseline* anchor reconstruction world frame.
    np.testing.assert_allclose(three_camera['thirdCamera']['translation'],np.array(cameras[2]['t'])/anchor_scale,atol=.045,rtol=0)


@pytest.mark.parametrize('planar', [False, True])
@pytest.mark.parametrize('distinct', [1, 8, 15])
def test_pnp_duplicate_rows_cannot_inflate_minimum_support(engine, planar, distinct):
    rows, *_ = synthetic(n=distinct, planar=planar)
    inflated = np.tile(rows, (max(2, 32 // distinct + 1), 1))
    assert len(inflated) >= 16
    with pytest.raises(ValueError, match='16 distinct'):
        engine.register_camera(inflated)


@pytest.mark.parametrize('planar', [False, True])
def test_pnp_duplicates_preserve_baseline_pose_and_first_original_row_indices(engine, planar):
    rows, *_ = synthetic(n=40, planar=planar)
    baseline = engine.register_camera(rows)
    duplicated = np.repeat(rows, 3, axis=0)
    actual = engine.register_camera(duplicated)
    assert actual['inputTracks'] == len(duplicated)
    assert actual['inlierIndices'] == [3 * i for i in baseline['inlierIndices']]
    assert actual['inlierCount'] == baseline['inlierCount'] == 40
    for field in ('rotation', 'translation', 'medianErrorNormalized', 'hypothesesTested',
                  'worldPlanarityRatio', 'initializationModel'):
        assert actual[field] == baseline[field]


@pytest.mark.parametrize('planar', [False, True])
def test_pnp_numeric_signed_zero_is_the_same_observation(engine, planar):
    rows, *_ = synthetic(n=32, planar=planar)
    rows[:, 0] -= rows[0, 0]  # Include exact positive zero; regenerate correct observations.
    angle = .16
    rotation = np.array([[np.cos(angle), 0, np.sin(angle)], [0, 1, 0],
                         [-np.sin(angle), 0, np.cos(angle)]])
    projected = rows[:, :3] @ rotation.T + np.array([-1.25, .11, .17])
    rows[:, 3:] = projected[:, :2] / projected[:, 2, None]
    baseline = engine.register_camera(rows)
    extra = rows.copy()
    extra[extra == 0] = -0.0
    actual = engine.register_camera(np.vstack([rows, extra]))
    assert actual['inlierIndices'] == baseline['inlierIndices']
    assert actual['inlierCount'] == baseline['inlierCount']
    assert actual['rotation'] == baseline['rotation']


@pytest.mark.parametrize('column', range(5))
def test_pnp_does_not_approximately_merge_distinct_observation_tuples(engine, column):
    rows, *_ = synthetic(n=15)
    extra = rows[0].copy()
    extra[column] = np.nextafter(extra[column], np.inf)
    actual = engine.register_camera(np.vstack([rows, extra]))
    assert actual['inputTracks'] == actual['inlierCount'] == 16
    assert actual['inlierIndices'] == list(range(16))


def test_pnp_conflicting_observation_does_not_hide_later_valid_same_xyz(engine):
    rows, *_ = synthetic(n=40)
    conflict = rows[0].copy()
    conflict[3:] = [9.0, -9.0]
    actual = engine.register_camera(np.vstack([conflict, rows, rows]))
    assert actual['inputTracks'] == 81
    assert actual['inlierIndices'] == list(range(1, 41))
    assert actual['inlierCount'] == 40


@pytest.mark.parametrize('bad', [np.nan, np.inf, 100001.])
def test_pnp_validates_all_raw_rows_before_deduplication(engine, bad):
    rows, *_ = synthetic(n=15)
    duplicated = np.tile(rows, (3, 1))
    duplicated[-1, 4] = bad
    with pytest.raises(ValueError, match='PnP coordinate'):
        engine.register_camera(duplicated)
