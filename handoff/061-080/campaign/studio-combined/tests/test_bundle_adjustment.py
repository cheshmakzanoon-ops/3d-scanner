"""Actual production joint bundle-adjustment kernel, independent host and photo checks."""
from __future__ import annotations
from pathlib import Path
import json
import sys
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_api import NativeEngine
from bundle_three_view import run

@pytest.fixture(scope='module')
def engine():return NativeEngine()

@pytest.fixture(scope='module')
def photographic():return run()

def fixture_rows(outliers:bool=False):
    rng=np.random.default_rng(127)
    true_cam=np.array([0.,-1.,-1.75]);estimate=np.array([0.,-1.,-1.71]);cams=[]
    for i,tx in enumerate(estimate):cams.append([1,0,0,0,1,0,0,0,1,tx,(0.018 if i==2 else 0),0])
    point=[];observ=[]
    for i in range(160):
        p=np.array([rng.uniform(-1,1),rng.uniform(-1,1),rng.uniform(3,8)])
        seed=p+rng.normal(0,.012,3)
        point.append([i,*seed,100,160,220])
        for j,tx in enumerate(true_cam):
            uv=(p+np.array([tx,0,0]))[:2]/p[2]+rng.normal(0,.00025,2)
            if outliers and i%12==0 and j==2: uv+=np.array([.2,-.16])
            observ.append([i,j,*uv,100,160,220])
    return np.asarray(cams),np.asarray(point),np.asarray(observ)

def test_joint_ba_improves_independent_perturbed_camera_and_points(engine):
    cams,pts,obs=fixture_rows()
    result=engine.bundle_adjust(cams,pts,obs,threshold=.025,iterations=16)
    assert result['optimization']=='coupled_huber_schur_lm'
    assert result['pointCount']>=155
    assert result['acceptedSteps']>0
    assert result['robustCostAfter']<.20*result['robustCostBefore']
    assert result['medianErrorAfterNormalized']<result['medianErrorBeforeNormalized']
    assert result['anchorCamerasFixed']==2
    assert result['cameras'][0]['rotation']==cams[0,:9].tolist()
    assert result['cameras'][1]['translation']==cams[1,9:].tolist()
    assert abs(result['cameras'][2]['translation'][0]-(-1.75))<abs(cams[2,9]-(-1.75))

def test_joint_ba_removes_large_reprojection_outliers(engine):
    cams,pts,obs=fixture_rows(outliers=True)
    result=engine.bundle_adjust(cams,pts,obs,threshold=.025)
    assert result['rejectedObservations']>=10
    assert result['pointCount']>=150
    assert result['robustCostAfter']<result['robustCostBefore']

def test_solver_is_deterministic_under_fixed_observations(engine):
    cams,pts,obs=fixture_rows()
    a=engine.bundle_adjust(cams,pts,obs,threshold=.025,iterations=3)
    b=engine.bundle_adjust(cams,pts,obs,threshold=.025,iterations=3)
    assert a==b

@pytest.mark.parametrize('kind',['nan_camera','nan_point','duplicate_point','fractional_track','unknown_camera','zero_baseline','excess_iterations','bad_threshold'])
def test_bad_geometry_rejected_explicitly(engine,kind):
    cams,pts,obs=fixture_rows()
    if kind=='nan_camera':cams[1,9]=np.nan
    if kind=='nan_point':pts[4,2]=np.nan
    if kind=='duplicate_point':pts[5,0]=pts[4,0]
    if kind=='fractional_track':obs[0,0]=.2
    if kind=='unknown_camera':obs[0,1]=3
    if kind=='zero_baseline':cams[1,9]=0
    iterations=26 if kind=='excess_iterations' else 6
    threshold=0 if kind=='bad_threshold' else .025
    with pytest.raises(ValueError):engine.bundle_adjust(cams,pts,obs,threshold,iterations)

def test_calibrated_synthetic_photographs_feed_actual_joint_solver(photographic):
    assert photographic['fixture'].startswith('synthetic')
    assert photographic['cameraCount']==3
    assert photographic['bundlePoints']>=200
    assert photographic['thirdViewTrackCount']>=100
    assert photographic['fixedAnchors']==2
    assert photographic['costAfter']<photographic['costBefore']
    assert photographic['medianErrorAfter']<=photographic['medianErrorBefore']
    assert photographic['acceptedSteps']>0
