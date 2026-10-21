import json
import numpy as np
import pytest
from tools.qa.geometry_quality.metrics import (evaluate,similarity,transform,topology,camera_metrics,point_surface_distances,surface_samples)
from tools.qa.geometry_quality.__main__ import load


def square():return np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0]],float),np.array([[0,1,2],[0,2,3]])


def test_exact_distances():
    v,f=square();d=point_surface_distances([[.2,.4,2],[2,0,0],[.8,.8,0]],v,f)
    assert d==pytest.approx([2,1,0],abs=1e-12)


def test_removed_side_cannot_improve_completeness():
    v,f=square();eligible=surface_samples(v,f,1000)
    full=evaluate(v,f,v,f,eligible,samples=256)
    half=evaluate(v,f[:1],v,f,eligible,samples=256)
    assert full['completeness_fraction']==1
    assert half['accuracy_p95']<1e-12
    assert .45<half['completeness_fraction']<.6


def test_similarity_known_scale_rotation_translation():
    v=np.array([[0,0,0],[1,0,0],[0,1,0],[0,0,1]],float)
    r=np.array([[0,-1,0],[1,0,0],[0,0,1]])
    y=3.7*v@r+[5,-2,8]
    align=similarity(v,y)
    assert align[0]==pytest.approx(3.7)
    assert transform(v,align)==pytest.approx(y)
    with pytest.raises(ValueError,match='degenerate'):similarity([[0,0,0],[1,0,0],[2,0,0]],[[0,0,0],[1,0,0],[2,0,0]])


def test_thin_disconnected_duplicate_nonmanifold():
    v,f=square()
    v=np.vstack([v,v+[0,0,.001]])
    f=np.vstack([f,f+4,f[:1]])
    result=topology(v,f)
    assert result['components']==2
    assert result['duplicate_triangles']==1
    assert result['nonmanifold_edges']>0
    assert not result['watertight_topology']


def test_coplanar_overlap_and_crossing():
    v=np.array([[0,0,0],[2,0,0],[0,2,0],[.2,.2,0],[1,.2,0],[.2,1,0]])
    f=np.array([[0,1,2],[3,4,5]])
    assert topology(v,f)['self_intersection_pairs']==1
    v[3:]=[[.2,.2,-1],[.2,.2,1],[1,1,.2]]
    assert topology(v,f)['self_intersection_pairs']==1
    assert topology(v,f,intersection_budget=1)['self_intersections_status']=='NOT_RUN_BUDGET'


def test_camera_registration_and_endpoint_drift():
    result=camera_metrics(['a','b','c'],['a','c'],[[0,0,0],[2,0,0]],[[0,0,0],[1,0,0]])
    assert result['registration_fraction']==pytest.approx(2/3)
    assert result['trajectory_endpoint_drift']==1
    assert result['unregistered_ids']==['b']
    with pytest.raises(ValueError):camera_metrics(['a'],['missing'])


@pytest.mark.parametrize('v,f',[([],[]),([[0,0,float('nan')]],[]),([[0,0,0]],[[0,1,2]]),([[0,0,0]],[[0.,0.,0.]])])
def test_malformed_geometry(v,f):
    with pytest.raises(ValueError):topology(v,f)


def test_fixed_reference_required():
    v,f=square()
    with pytest.raises(ValueError,match='eligible'):evaluate(v,f,v,f,[])
    result=evaluate(v,[],v,f,surface_samples(v,f,100),samples=100)
    assert result['topology']['representation']=='points'
    assert result['completeness_fraction']<.1


def test_obj_and_native_ply(tmp_path):
    p=tmp_path/'mesh.obj';p.write_text('v 0 0 0\nv 1 0 0\nv 0 1 0\nf -3 -2 -1\n')
    v,f=load(p);assert f.tolist()==[[0,1,2]]
    p=tmp_path/'mesh.ply';p.write_text('ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n1 0 0\n0 1 0\n3 0 1 2\n')
    a,b=load(p);assert np.array_equal(a,v) and np.array_equal(b,f)


def test_shared_vertex_coplanar_overlap_is_defect():
    v=np.array([[0,0,0],[2,0,0],[0,2,0],[1,0,0],[0,1,0]],float)
    assert topology(v,np.array([[0,1,2],[0,3,4]]))['self_intersection_pairs']==1


def test_known_camera_orientation_error():
    rotation=np.array([[0,-1,0],[1,0,0],[0,0,1]],float)
    result=camera_metrics(['a'],['a'],estimated_rotations=[rotation],reference_rotations=[np.eye(3)])
    assert result['rotation_error_deg_p95']==pytest.approx(90)


def tetrahedron():
    return (np.array([[0,0,0],[1,0,0],[0,1,0],[0,0,1]],float),
            np.array([[0,2,1],[0,1,3],[0,3,2],[1,2,3]]))


def test_pinched_closed_tetrahedra_are_not_watertight():
    v,f=tetrahedron()
    vertices=np.vstack([v,-v[1:]])
    faces=np.vstack([f,np.array([0,4,5,6])[f]])
    result=topology(vertices,faces)
    assert result['boundary_edges']==result['nonmanifold_edges']==0
    assert result['self_intersection_pairs']==0
    assert not result['watertight_topology']
    assert result['representation']=='open_or_defective_triangle_surface'
    assert result['nonmanifold_vertices']==1


def vertex_link_case(name):
    v,f=tetrahedron()
    if name=='closed_tetra':return v,f,0,True,1
    if name=='disconnected_closed':return np.vstack([v,v+3]),np.vstack([f,f+4]),0,True,2
    if name=='coincident_distinct_ids':return np.vstack([v,-v]),np.vstack([f,f+4]),0,True,2
    if name=='pinched_closed':return np.vstack([v,-v[1:]]),np.vstack([f,np.array([0,4,5,6])[f]]),1,False,1
    if name=='single_triangle':return v,f[:1],0,False,1
    if name=='open_disk':
        v=np.array([[0,0,0],[1,0,0],[0,1,0],[-1,0,0],[0,-1,0]],float)
        return v,np.array([[0,1,2],[0,2,3],[0,3,4],[0,4,1]]),0,False,1
    if name=='boundary_fan':
        v=np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0],[-1,1,0]],float)
        return v,np.array([[0,1,2],[0,2,3],[0,3,4]]),0,False,1
    if name=='bowtie':
        return np.vstack([v,-v[1:3]]),np.array([[0,1,2],[0,4,5]]),1,False,1
    if name=='branched_nonmanifold_edge':
        return np.vstack([v,[[0,-1,0]]]),np.array([[0,1,2],[0,3,1],[0,1,4]]),2,False,1
    if name=='duplicate_face':return v,np.vstack([f,f[:1]]),3,False,1
    if name=='opposite_duplicate_face':return v,np.vstack([f[:1],f[:1,::-1]]),3,False,1
    if name=='repeated_index':return v,np.array([[0,0,1]]),2,False,1
    if name=='geometric_degenerate':
        return np.array([[0,0,0],[1,0,0],[2,0,0]],float),np.array([[0,1,2]]),0,False,1
    if name=='cycle_and_path':
        return np.vstack([v,-v[1:3]]),np.vstack([f,[[0,4,5]]]),1,False,1
    raise AssertionError(name)


@pytest.mark.parametrize('name',[
    'closed_tetra','disconnected_closed','coincident_distinct_ids','pinched_closed',
    'single_triangle','open_disk','boundary_fan','bowtie','branched_nonmanifold_edge',
    'duplicate_face','opposite_duplicate_face','repeated_index','geometric_degenerate','cycle_and_path'])
@pytest.mark.parametrize('permutation',['original','reindexed','reversed_winding'])
@pytest.mark.parametrize('budget',[0,5000])
def test_indexed_vertex_links(name,permutation,budget):
    v,f,invalid,watertight,components=vertex_link_case(name)
    if permutation=='reindexed':
        v=v[::-1];f=(len(v)-1-f[::-1])[:,[1,2,0]]
    elif permutation=='reversed_winding':f=f[:,::-1]
    result=topology(v,f,intersection_budget=budget)
    assert result['nonmanifold_vertices']==invalid
    assert result['watertight_topology']==watertight
    assert result['components']==components
    assert result['representation']==('closed_manifold_candidate' if watertight else 'open_or_defective_triangle_surface')
    assert result['self_intersections_status']==('NOT_RUN_BUDGET' if budget==0 else 'PASS_CHECK_EXECUTED')
    if budget==0:assert result['self_intersection_pairs'] is None
    if name=='geometric_degenerate':assert result['degenerate_triangles']==1
    if name=='repeated_index':assert result['degenerate_triangles']==1
    if name in ('duplicate_face','opposite_duplicate_face'):assert result['duplicate_triangles']==1
    if name=='branched_nonmanifold_edge':assert result['nonmanifold_edges']==1


def test_points_topology_contract_unchanged():
    v,_=tetrahedron()
    assert topology(v,[])=={'representation':'points','self_intersections_status':'NOT_APPLICABLE','components':4}
