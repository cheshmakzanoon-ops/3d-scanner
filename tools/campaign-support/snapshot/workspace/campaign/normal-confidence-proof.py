"""Independent analytic confidence-PCA evidence. No photographs or held-out benchmark inputs.

The model remains a geometric/confidence heuristic, never calibrated physical sigma.
No production file is changed. All nine strictly positive weights are retained.
"""
from pathlib import Path
import hashlib,json,math,os,subprocess,tempfile
import numpy as np

OUT=Path('/workspace/campaign/normal-confidence-proof.json')
HEADER=Path('/workspace/agents/054/reconstruction/include/realityforge/surface_evidence.hpp')
LIBRARY=Path('/workspace/agents/004/build/host/librealityforge.so')

def jacobi(cov):
    # Faithful port of the production rotation, checked against independent LAPACK eigh.
    a=cov.copy();e=np.eye(3)
    for iteration in range(32):
        p,q=max(((0,1),(0,2),(1,2)),key=lambda pair:abs(a[pair]))
        if abs(a[p,q])<=np.finfo(float).eps*max(a[0,0],a[1,1],a[2,2],1e-300):break
        phi=.5*math.atan2(2*a[p,q],a[q,q]-a[p,p]);c=math.cos(phi);s=math.sin(phi)
        pp=c*c*a[p,p]-2*c*s*a[p,q]+s*s*a[q,q]
        qq=s*s*a[p,p]+2*c*s*a[p,q]+c*c*a[q,q]
        for k in range(3):
            if k not in (p,q):
                kp=c*a[k,p]-s*a[k,q];kq=s*a[k,p]+c*a[k,q]
                a[k,p]=a[p,k]=kp;a[k,q]=a[q,k]=kq
        a[p,p]=pp;a[q,q]=qq;a[p,q]=a[q,p]=0
        ep=e[:,p].copy();eq=e[:,q].copy();e[:,p]=c*ep-s*eq;e[:,q]=s*ep+c*eq
    order=np.argsort(a.diagonal());return a.diagonal()[order],e[:,order]

def fixture(delta,scale=1.):
    # Independently specified truth: frontoparallel plane z=5*scale.
    # Actual pinhole image grid: cx=cy=30, focal200, step3; one positive-depth corner outlier.
    coordinates=np.array([(x*3,y*3) for y in (-1,0,1) for x in (-1,0,1)],float)
    depth=np.full(9,5*scale);depth[-1]=(5+delta)*scale
    depth=depth.astype(np.float32).astype(float) # Actual DenseDepthMap stores float camera-z.
    assert np.all(np.isfinite(depth)) and np.all(depth>0)
    assert np.max(abs(depth-depth[4])/depth[4])<.08
    points=np.column_stack((coordinates[:,0]*depth/200,coordinates[:,1]*depth/200,depth))
    return points,depth

def fit(points,weights,truth=np.array([0.,0.,1.])):
    assert len(points)==len(weights)==9 and np.all(np.isfinite(weights)) and np.all((weights>0)&(weights<=1))
    total=weights.sum();effective=total*total/(weights@weights)
    center=(points*weights[:,None]).sum(axis=0)/total
    centered=points-center;cov=(centered*weights[:,None]).T@centered/total
    values,vectors=np.linalg.eigh(cov);normal=vectors[:,0]
    jvalues,jvectors=jacobi(cov)
    np.testing.assert_allclose(values,jvalues,rtol=2e-12,atol=np.linalg.norm(cov)*2e-15)
    assert abs(normal@jvectors[:,0])>1-1e-12
    ratio=values[0]/values[1];conditioning=values[1]/values[2]
    supported=effective>=5 and conditioning>=.02 and ratio<=.08
    return {'effective_support':float(effective),'observations_retained':9,'minimum_weight':float(weights.min()),
        'small_mid_ratio':float(ratio),'conditioning':float(conditioning),'normal_error_degrees':float(np.degrees(np.arccos(np.clip(abs(normal@truth),0,1)))),
        'supported_by_unchanged_gates':bool(supported),'normal':normal.tolist(),'center':center.tolist(),
        'plane_residual':float(np.sqrt(max(values[0],0))),'jacobi_relative_eigen_error':float(np.max(abs(values-jvalues))/np.linalg.norm(cov))}

unit=np.ones(9);confidence=unit.copy();confidence[-1]=1/8
records=[]
for delta in (.05,.1):
    for scale in (.01,1.,100.):
        points,depth=fixture(delta,scale)
        uniform=fit(points,unit);weighted=fit(points,confidence)
        assert weighted['normal_error_degrees']<uniform['normal_error_degrees']/4
        assert weighted['supported_by_unchanged_gates']
        if delta==.05:assert uniform['supported_by_unchanged_gates'] # Improvement also when both already pass.
        if delta==.1:assert not uniform['supported_by_unchanged_gates']
        records.append({'outlier_delta_at_unit_scale':delta,'world_scale':scale,'maximum_depth_jump':float(np.max(abs(depth-depth[4])/depth[4])),
            'uniform':uniform,'confidence_weighted':weighted})
# Pure-plane weighting does not invent a different normal.
points,_=fixture(0.)
assert fit(points,confidence)['normal_error_degrees']<1e-5
# Default weights are exactly the old unweighted covariance; common positive rescaling is invariant.
reference=fit(points,unit);rescaled=fit(points,unit/8)
np.testing.assert_allclose(abs(np.array(reference['normal'])),abs(np.array(rescaled['normal'])),atol=1e-12)
# All9 retained but only3 carry meaningful mass: effective support fails existing minimum5.
few=np.full(9,1/128);few[[0,4,8]]=1
assert fit(points,few)['effective_support']<5
assert not fit(points,few)['supported_by_unchanged_gates']
# Rigid world rotation/translation must preserve error and eligibility.
a=.37;b=-.51
rotation=np.array([[math.cos(a),-math.sin(a),0],[math.sin(a),math.cos(a),0],[0,0,1]])@np.array([[math.cos(b),0,math.sin(b)],[0,1,0],[-math.sin(b),0,math.cos(b)]])
points,_=fixture(.1);rotated=fit(points@rotation.T+np.array([1.,-2.,3.]),confidence,rotation@np.array([0.,0.,1.]))
assert abs(rotated['normal_error_degrees']-fit(points,confidence)['normal_error_degrees'])<1e-9
# Execute the actual current header unweighted baseline (small standalone probe, no product build).
cpp=r'''
#include "realityforge/surface_evidence.hpp"
#include <iostream>
#include <iomanip>
int main(){
 rf::DenseView view;view.width=view.height=64;view.fx=view.fy=200;view.cx=view.cy=30;view.pose.rotation=rf::identity();
 for(double delta:{.05,.1}){
  rf::DenseDepthMap map;map.width=map.height=64;map.depth.assign(4096,0);map.confidence.assign(4096,1);
  for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx)map.depth[(30+3*dy)*64+30+3*dx]=static_cast<float>(5+((dx==1&&dy==1)?delta:0));
  map.confidence[33*64+33]=.125f;
  const auto out=rf::estimateDepthNeighborhoodNormal(view,map,30,30,3);
  if(out.validity!=(delta==.05?rf::NormalValidity::Supported:rf::NormalValidity::IllConditioned))return 1;
  std::cout<<std::setprecision(17)<<delta<<' '<<static_cast<int>(out.validity)<<' '<<out.uncertainty.planeResidual<<' '<<out.uncertainty.conditioning<<'\n';
 }
}
'''
with tempfile.TemporaryDirectory(prefix='normal-confidence-proof-') as folder:
    folder=Path(folder);source=folder/'probe.cpp';source.write_text(cpp);exe=folder/'probe'
    subprocess.run(['c++','-std=c++20','-O1','-Wall','-Wextra','-Wpedantic','-Werror','-I/workspace/agents/054/reconstruction/include',str(source),'-L'+str(LIBRARY.parent),'-Wl,-rpath,'+str(LIBRARY.parent),'-lrealityforge','-o',str(exe)],check=True,timeout=90)
    actual=subprocess.check_output([str(exe)],text=True,timeout=20)
    for line in actual.splitlines():
        delta,validity,residual,conditioning=line.split();match=next(item for item in records if item['world_scale']==1. and item['outlier_delta_at_unit_scale']==float(delta))
        np.testing.assert_allclose(float(residual),match['uniform']['plane_residual'],rtol=1e-11)
        np.testing.assert_allclose(float(conditioning),match['uniform']['conditioning'],rtol=1e-11)
report={'schema':1,'photographs_or_real_benchmark_used':False,'production_edits':False,'input_model':'actual9pinholesamples: z_truth5, focal200, step3, one corner positive outlier +.05 or +.1, weight1/8 vs other8 weights1',
 'estimator':'mu=sum(w*p)/sum(w); C=sum(w*(p-mu)*(p-mu)^T)/sum(w); smallest eigenvector',
 'unchanged_gates':{'depth_relative_jump':.08,'maximum_small_mid_ratio':.08,'minimum_mid_large_ratio':.02,'minimum_effective_support':5,'observations_required':9},
 'effective_support_formula':'(sum(w))^2/sum(w^2)','calibrated_physical_sigma':False,
 'limits':'An analytic robustness counterexample supports the model when lower confidence carries less reliable depth; NCC confidence is not a calibrated inverse variance or a guarantee on arbitrary captures. Zero/nonfinite confidence policy must reject the whole neighborhood or explicitly represent unavailable confidence, never silently drop observations. No claims about a held-out fusion count.',
 'header_sha256':hashlib.sha256(HEADER.read_bytes()).hexdigest(),'library_sha256':hashlib.sha256(LIBRARY.read_bytes()).hexdigest(),'actual_unweighted_header_probe':actual.strip().splitlines(),
 'checks_passed':['all9positive observations retained','effective support>=5','both-supported normal improvement','rejected-to-supported fixed-gate improvement','scales.01/1/100','exactplane preserved','unitweight/default parity','commonweightscale invariant','effectiveN<5 rejected','rigid transform invariant','Jacobi matches independent LAPACK','actual production baseline matches NumPy'],
 'analytic_cases':records}
OUT.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'report':str(OUT),'checks':len(report['checks_passed']),'key_case':next(item for item in records if item['world_scale']==1. and item['outlier_delta_at_unit_scale']==.1)},indent=2))
