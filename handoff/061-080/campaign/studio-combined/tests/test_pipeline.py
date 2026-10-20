"""Executable acceptance tests against the actual C++ core; no mocked geometry."""
from __future__ import annotations
import json
from pathlib import Path
import struct
import sys
import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"tools"))
from native_api import NativeEngine
from photo_pair import extract_matches, run
from make_fixture import surface_z

@pytest.fixture(scope="session")
def engine(): return NativeEngine()


def observations(n=180, noise=0., outliers=0, seed=82, baseline=1., rotation=.08, planar=False):
    rng=np.random.default_rng(seed)
    xyz=np.column_stack([rng.uniform(-1,1,(n,2)),rng.uniform(3,8,n)])
    if planar: xyz[:,2]=4
    a=rotation
    r=np.array([[np.cos(a),0,np.sin(a)],[0,1,0],[-np.sin(a),0,np.cos(a)]])
    t=np.array([-baseline,0,0])
    second=xyz@r.T+t
    p=xyz[:,:2]/xyz[:,2,None];q=second[:,:2]/second[:,2,None]
    p+=rng.normal(0,noise,p.shape);q+=rng.normal(0,noise,q.shape)
    q[:outliers]=rng.uniform(-.5,.5,(outliers,2))
    return np.column_stack([p,q,rng.integers(0,256,(n,3))]),xyz,r,t

@pytest.mark.parametrize("seed",[1,7,19,82,131])
def test_exact_geometry_different_seeds(engine,seed):
    rows,truth,r,t=observations(seed=seed)
    result=engine.reconstruct(rows)
    points=np.array(result["points"])
    ids=points[:,8].astype(int)
    assert len(points)>=170
    assert np.median(np.linalg.norm(points[:,:3]-truth[ids],axis=1))<1e-5
    assert np.linalg.norm(np.array(result["rotation"]).reshape(3,3)-r)<1e-5

@pytest.mark.parametrize("outliers",[20,45,65])
def test_outliers_with_noise(engine,outliers):
    rows,_,r,_=observations(noise=.00018,outliers=outliers)
    result=engine.reconstruct(rows)
    assert result["pointCount"]>=180-outliers-12
    assert np.linalg.norm(np.array(result["rotation"]).reshape(3,3)-r)<.035
    assert result["translation"][0]<-.98

@pytest.mark.parametrize("value",[np.nan,np.inf,-np.inf])
def test_nonfinite_input_rejected(engine,value):
    rows,*_=observations();rows[4,1]=value
    with pytest.raises(ValueError,match="Invalid normalized"): engine.reconstruct(rows)

@pytest.mark.parametrize("threshold",[0,-.001,.2,np.nan])
def test_invalid_settings_rejected(engine,threshold):
    rows,*_=observations()
    with pytest.raises(ValueError,match="Invalid reconstruction settings"):engine.reconstruct(rows,threshold)

@pytest.mark.parametrize("count",[0,1,7,19])
def test_too_few_matches(engine,count):
    rows,*_=observations(count)
    with pytest.raises(ValueError,match="Too few matches"):engine.reconstruct(rows)


def test_pure_rotation_rejected(engine):
    rows,*_=observations(baseline=0)
    with pytest.raises(ValueError):engine.reconstruct(rows)


def test_low_parallax_rejected(engine):
    rows,*_=observations(baseline=.0001)
    with pytest.raises(ValueError):engine.reconstruct(rows)


def test_exact_planar_degeneracy_rejected(engine):
    rows,*_=observations(planar=True)
    with pytest.raises(ValueError):engine.reconstruct(rows)


def test_export_failure_is_reported(engine,tmp_path):
    rows,*_=observations()
    with pytest.raises(ValueError,match="Cannot open GLB"):engine.reconstruct(rows,glb=tmp_path/"not_created"/"file.glb")


def test_deterministic_same_seed(engine):
    rows,*_=observations(noise=.0001,outliers=20)
    assert engine.reconstruct(rows)==engine.reconstruct(rows)


def parse_glb(path:Path):
    blob=path.read_bytes()
    magic,version,length=struct.unpack_from("<III",blob)
    assert magic==0x46546C67 and version==2 and length==len(blob)
    json_size,json_tag=struct.unpack_from("<II",blob,12)
    assert json_size%4==0 and json_tag==0x4E4F534A
    document=json.loads(blob[20:20+json_size])
    bin_size,bin_tag=struct.unpack_from("<II",blob,20+json_size)
    binary=blob[28+json_size:]
    assert bin_tag==0x004E4942 and bin_size==len(binary) and bin_size%4==0
    return document,binary


def test_glb_point_contract_and_bounds(engine,tmp_path):
    rows,*_=observations()
    result=engine.reconstruct(rows,glb=tmp_path/"sparse.glb")
    document,binary=parse_glb(tmp_path/"sparse.glb")
    assert document["meshes"][0]["primitives"][0]["mode"]==0
    assert document["extras"]["notSurfaceMesh"] is True
    assert document["extras"]["scale"]=="arbitrary"
    accessor=document["accessors"][0];count=accessor["count"]
    assert count==result["pointCount"]
    points=np.frombuffer(binary[:count*12],dtype="<f4").reshape(-1,3)
    np.testing.assert_allclose(points.min(0),accessor["min"],rtol=1e-6)
    np.testing.assert_allclose(points.max(0),accessor["max"],rtol=1e-6)
    original=np.array(result["points"])[:,:3]
    np.testing.assert_allclose(points,original*[1,-1,-1],rtol=1e-6,atol=1e-6)
    colors=np.frombuffer(binary[count*12:],dtype="u1").reshape(-1,4)
    assert (colors[:,3]==255).all()


def test_ply_output_counts(engine,tmp_path):
    rows,*_=observations()
    result=engine.reconstruct(rows,ply=tmp_path/"sparse.ply")
    header,body=(tmp_path/"sparse.ply").read_text().split("end_header\n")
    assert f"element vertex {result['pointCount']}" in header
    assert len(body.splitlines())==result["pointCount"]
    assert "element face" not in header

@pytest.fixture(scope="session")
def photo_result(tmp_path_factory):
    folder=ROOT/"benchmarks/fixtures/relief"
    meta=json.loads((folder/"calibration.json").read_text())
    c1,c2=meta["cameras"][:2]
    output=tmp_path_factory.mktemp("photo-result")
    result=run(folder/c1["image"],folder/c2["image"],np.array(c1["K"]),np.array(c2["K"]),output)
    return result,meta,output


def test_photographs_use_sift_and_actual_core(photo_result):
    result,meta,_=photo_result
    assert meta["synthetic"] is True
    assert result["firstFeatures"]>500 and result["reciprocalMatches"]>200
    assert result["pointCount"]>250
    assert result["registeredCameras"]==2
    assert result["representation"]=="sparse_points"


def test_photo_pose_accuracy(photo_result):
    result,meta,_=photo_result
    true_r=np.array(meta["cameras"][1]["R"])
    r=np.array(result["rotation"]).reshape(3,3)
    angle=np.degrees(np.arccos(np.clip((np.trace(r@true_r.T)-1)/2,-1,1)))
    assert angle<1.,f"Rotation error {angle} degrees"
    t=np.array(meta["cameras"][1]["t"]);t/=np.linalg.norm(t)
    assert np.dot(t,result["translation"])>.995


def test_photo_surface_accuracy(photo_result):
    result,meta,_=photo_result
    baseline=np.linalg.norm(meta["cameras"][1]["center"])
    xyz=np.array(result["points"])[:,:3]*baseline
    errors=np.abs(xyz[:,2]-surface_z(xyz[:,0],xyz[:,1]))
    # Synthetic camera-space regression threshold, not a claim of physical millimeter accuracy.
    assert np.median(errors)<.08
    assert np.quantile(errors,.9)<.16


def test_photo_exports_structurally_valid(photo_result):
    result,_,output=photo_result
    document,_=parse_glb(output/"sparse.glb")
    assert document["accessors"][0]["count"]==result["pointCount"]


def test_python_bridge_shape_validation(engine):
    with pytest.raises(ValueError,match="N×7"):engine.reconstruct(np.zeros((3,4)))
