import numpy as np
import pytest
from tools.qa.texture_quality.metrics import (linear_rgb,sample,uv_metrics,alpha_metrics,held_out_metrics,render,overlap_area)


def fixture():
    v=np.array([[0,0,1],[7,0,1],[7,7,1],[0,7,1]],float)
    f=np.array([[0,1,2],[0,2,3]])
    uv=np.array([[.5,.5],[7.5,.5],[7.5,7.5],[.5,7.5]])/8
    y,x=np.mgrid[:8,:8];tex=np.stack([x/7,y/7,(x+y)/14,np.ones((8,8))],-1)
    return v,f,uv,tex


def test_linear_transfer_and_pixel_center():
    assert linear_rgb([0,.04045,1])==pytest.approx([0,.0031308049535603713,1])
    _,_,_,tex=fixture()
    assert sample(tex,[[.5/8,.5/8],[7.5/8,7.5/8]])==pytest.approx(np.array([tex[0,0],tex[7,7]]))


def test_uv_bounds_missing_overlap_and_distortion():
    v,f,uv,tex=fixture();m=uv_metrics(v,f,uv,tex)
    assert m['uv_overlap_pairs']==0 and m['chart_count']==1
    assert m['distortion_anisotropy_p95']==pytest.approx(1)
    assert m['supported_face_fraction_original']==1
    bad=uv.copy();bad[0]=[-1,0]
    assert uv_metrics(v,f,bad,tex)['uv_out_of_bounds_vertices']==1
    flat=np.zeros_like(uv)
    assert uv_metrics(v,f,flat,tex)['degenerate_uv_faces']==2
    assert uv_metrics(np.vstack([v,v]),np.vstack([f,f+4]),np.vstack([uv,uv]),tex)['uv_overlap_pairs']==2


def test_removing_hard_faces_retains_denominator():
    v,f,uv,tex=fixture()
    result=uv_metrics(v,f[:1],uv,tex,original_face_count=2)
    assert result['retained_geometry_fraction']==.5
    assert result['supported_face_fraction_original']==.5
    tex[:,:,:]=0
    assert uv_metrics(v,f,uv,tex)['supported_faces']==0


def test_opaque_unknown_and_missing_known_alpha():
    _,_,_,tex=fixture();mask=np.ones((8,8),bool);mask[0]=False
    result=alpha_metrics(tex,mask);assert result['opaque_unknown_pixels']==8
    tex[1,:,3]=0
    assert alpha_metrics(tex,mask)['missing_known_pixels']==8


def test_calibrated_checker_detects_flip_color_space_alpha_substitution_projection():
    v,f,uv,tex=fixture();mask=np.ones((8,8),bool)
    image,_=render(v,f,uv,tex,8,8,np.eye(3),np.eye(3),[0,0,0])
    perfect=held_out_metrics(image,tex,mask)
    assert perfect['linear_rgb_rmse']<1e-14 and perfect['held_out_support_fraction']==1
    flipped,_=render(v,f,np.column_stack([uv[:,0],1-uv[:,1]]),tex,8,8,np.eye(3),np.eye(3),[0,0,0])
    assert held_out_metrics(flipped,tex,mask)['linear_rgb_rmse']>.25
    wrong=tex.copy();wrong[:,:,:3]=linear_rgb(tex[:,:,:3])
    assert held_out_metrics(wrong,tex,mask)['linear_rgb_rmse']>.1
    opaque=tex.copy();opaque[:4,:,3]=0
    assert held_out_metrics(opaque,tex,mask)['held_out_support_fraction']==.5
    substitute=tex.copy();substitute[:,:,:3]=.5
    assert held_out_metrics(substitute,tex,mask)['linear_rgb_rmse']>.2
    shifted,_=render(v,f,uv,tex,8,8,np.eye(3),np.eye(3),[2,0,0])
    assert held_out_metrics(shifted,tex,mask)['held_out_support_fraction']<.8


def test_occlusion_mask_is_independent_and_errors_do_not_hide_missing():
    _,_,_,tex=fixture();prediction=tex.copy();prediction[:4]=0
    mask=np.ones((8,8),bool)
    assert held_out_metrics(prediction,tex,mask)['held_out_support_fraction']==.5
    mask[:4]=False
    assert held_out_metrics(prediction,tex,mask)['held_out_support_fraction']==1
    mask[:]=False
    with pytest.raises(ValueError):held_out_metrics(prediction,tex,mask)


def test_seam_discontinuity_across_duplicate_vertices():
    v=np.array([[0,0,0],[1,0,0],[0,1,0],[1,0,0],[1,1,0],[0,1,0]],float)
    f=np.array([[0,1,2],[3,4,5]]);uv=np.array([[.05,.05],[.45,.05],[.05,.95],[.55,.05],[.95,.95],[.55,.95]])
    tex=np.ones((32,32,4));tex[:,:16,:3]=0
    result=uv_metrics(v,f,uv,tex)
    assert result['potential_seam_edges']==1
    assert result['seam_linear_rgb_abs_p95']>.9


def test_overlap_clipping():
    a=np.array([[0,0],[1,0],[0,1]],float)
    assert overlap_area(a,a)==pytest.approx(.5)
    assert overlap_area(a,a+2)==0


def test_invalid_inputs_and_budget():
    v,f,uv,tex=fixture()
    with pytest.raises(ValueError):uv_metrics(v,f,uv[:-1],tex)
    with pytest.raises(ValueError):uv_metrics(v,f,uv,tex,original_face_count=1)
    assert uv_metrics(v,f,uv,tex,overlap_budget=1)['overlap_status']=='NOT_RUN_BUDGET'


def test_embedded_png_substitution_and_reproducible_fixture(tmp_path):
    from tools.qa.texture_quality.fixture import generate
    from tools.qa.texture_quality.assets import load
    a,b=tmp_path/'a',tmp_path/'b';pa,pb=generate(a),generate(b)
    assert pa.read_bytes()==pb.read_bytes()
    v,f,uv,tex,meta=load(pa,a/'checker.png')
    assert len(f)==2 and tex.shape==(16,16,4)
    (b/'checker.png').write_bytes(b'wrong')
    with pytest.raises(ValueError,match='substitution'):load(pa,b/'checker.png')


def test_perspective_depth_occlusion():
    v,f,uv,tex=fixture()
    # Near green surface must occlude farther red surface at the same pixels.
    v=np.array([[0,0,1],[7,0,1],[0,7,1],[0,0,2],[14,0,2],[0,14,2]],float)
    f=np.array([[0,1,2],[3,4,5]])
    uv=np.array([[.75,.5]]*3+[[.25,.5]]*3)
    tex=np.ones((8,8,4));tex[:,:4,:3]=[1,0,0];tex[:,4:,:3]=[0,1,0]
    image,depth=render(v,f,uv,tex,8,8,np.eye(3),np.eye(3),[0,0,0])
    assert image[1,1,:3]==pytest.approx([0,1,0]);assert depth[1,1]==1


def test_geometry_retention_detects_substitution_and_duplicate_faces():
    from tools.qa.texture_quality.metrics import geometry_retention
    v,f,_,_=fixture()
    assert geometry_retention(v,f[:1],v,f)['retained_original_face_fraction']==.5
    with pytest.raises(ValueError,match='absent'):geometry_retention(v+1,f,v,f)
    with pytest.raises(ValueError,match='duplicated'):geometry_retention(v,np.vstack([f,f[:1]]),v,f)


def test_measured_legacy_overlap_minimal_reproducer():
    import json
    from pathlib import Path
    pair=json.loads((Path(__file__).resolve().parents[1]/'tools/qa/texture_quality/fixtures/middlebury-overlap.json').read_text())['pair']
    assert overlap_area(np.asarray(pair['uv_a']),np.asarray(pair['uv_b']))==pytest.approx(pair['area'],abs=1e-12)
    assert pair['area']>1e-6


def test_distortion_uses_pixel_aspect_not_normalized_uv():
    v=np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0]],float)
    f=np.array([[0,1,2],[0,2,3]])
    uv=np.array([[0,0],[.5,0],[.5,1],[0,1]],float)
    assert uv_metrics(v,f,uv,np.ones((8,16,4)))['distortion_anisotropy_p95']==pytest.approx(1)


@pytest.mark.parametrize('defect',['missing_uv','missing_texture','repeat_sampler','truncated_image'])
def test_hostile_actual_glb_metadata(tmp_path,defect):
    import json,struct
    from tools.qa.texture_quality.fixture import generate
    from tools.qa.texture_quality.assets import load
    from tools.qa.geometry_quality.glb import read
    path=generate(tmp_path);data,binary=read(path)
    if defect=='missing_uv':del data['meshes'][0]['primitives'][0]['attributes']['TEXCOORD_0']
    elif defect=='missing_texture':data['images'][0]['uri']='https://invalid.example/image.png'
    elif defect=='repeat_sampler':data['samplers'][0]['wrapS']=10497
    else:data['bufferViews'][3]['byteLength']=10
    js=json.dumps(data).encode();js+=b' '*((-len(js))%4)
    path.write_bytes(struct.pack('<III',0x46546c67,2,28+len(js)+len(binary))+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(binary),0x004e4942)+binary)
    with pytest.raises(ValueError):load(path)


def test_compact_adapter_missing_abi_records_not_run(tmp_path,monkeypatch):
    import json,sys,types
    from tools.qa.texture_quality.compact import main
    class MissingCompactEngine:
        def __init__(self,*args):self.library=object()
    monkeypatch.setitem(sys.modules,'native_api',types.SimpleNamespace(NativeEngine=MissingCompactEngine))
    monkeypatch.setattr(sys,'argv',['compact','--native-library','absent.so','--candidate-source-sha','a'*40,'--output-dir',str(tmp_path)])
    assert main()==2
    assert json.loads((tmp_path/'compact-receipt.json').read_text())['status']=='NOT_RUN'
