"""Evaluation-only immutable-point reachability bound; no solver, tuning or fitting."""
import sys
sys.dont_write_bytecode=True
import pathlib,json,hashlib,numpy as np
ROOT=pathlib.Path('/workspace/scratch/6b683a119bde/3d-scanner')
OUT=pathlib.Path(__file__).parent
paths={
'fixture':ROOT/'docs/development/experiments/new-cuboid-eight-view-160x120/evaluation-only/fixture.json',
'evaluator':ROOT/'docs/development/experiments/source-cell-binding-r1/source/cuboid_evaluate.py',
'accumulators':pathlib.Path('/workspace/shared/fixed-point-front-whole-first/geometry/bridge/accumulators.txt'),
'native_accumulators':pathlib.Path('/workspace/shared/source-cell-binding-corrected-run/observer/accumulators.tsv'),
'bridge_summary':pathlib.Path('/workspace/shared/fixed-point-front-whole-first/geometry/bridge/bridge-summary.json'),
'samples':pathlib.Path('/workspace/shared/fixed-point-front-whole-first/geometry/bridge/samples.txt'),
'baseline_evaluation':pathlib.Path('/workspace/shared/source-cell-binding-corrected-run/evaluation.json')}
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
pins={k:{'path':str(p),'sha256':sha(p)} for k,p in paths.items()}
sys.path.insert(0,str(paths['evaluator'].parent))
import cuboid_evaluate as ev
m=json.loads(paths['fixture'].read_text());ev.validate_fixture(m)
lines=paths['accumulators'].read_text().splitlines();header=lines[0].split();rows=[s.split() for s in lines[1:]]
assert '\n'.join(lines[1:]).strip()==paths['native_accumulators'].read_text().strip()
assert len(rows)==4855 and [int(r[0]) for r in rows]==list(range(4855))
p=np.asarray([[float(r[header.index(c)]) for c in ['px','py','pz']] for r in rows]);assert np.isfinite(p).all()
memberships=sum(int(r[header.index('sourceCount')]) for r in rows);assert memberships==9171
obs=len(paths['samples'].read_text().splitlines());assert obs==9172
rho=json.loads(paths['bridge_summary'].read_text())['rho'];assert rho==0.04965026414510365
v=np.asarray(m['vertices']);t=np.asarray(m['triangles']);n=ev.normals(v,t)
q,ids=ev.sample(v,t);mask=ev.observable_convex(q,n[ids],m['cameras'],m['width'],m['height'])>=2
visible=q[mask];faces=ids[mask];assert len(visible)==3734
nearest=[];indices=[]
for chunk in np.array_split(visible,60):
 d2=np.sum((chunk[:,None,:]-p[None,:,:])**2,axis=2);idx=d2.argmin(axis=1)
 nearest.extend(np.sqrt(d2[np.arange(len(chunk)),idx]));indices.extend(idx)
d=np.asarray(nearest);diag=float(np.linalg.norm(np.ptp(v,axis=0)));slack=1e-12*max(1,diag)
base=json.loads(paths['baseline_evaluation'].read_text());assert base['referenceBoundingBoxDiagonal']==diag
curves={}
for f in [0,0.005,0.01,0.02,0.05]:
 threshold=2*rho+diag*f+slack;reachable=d<=threshold
 curves[str(f)]={'toleranceWorld':diag*f,'necessaryPointDistanceThreshold':threshold,'potentiallyReachableSamples':int(reachable.sum()),'provablyUnreachableSamples':int((~reachable).sum()),'completenessUpperBound':float(reachable.mean()),'unreachableFraction':float((~reachable).mean()),'baselineCompleteness':base['referenceSurfaceSampling']['distanceCDF'].get(str(f))}
 if f:curves[str(f)]['maxPossibleGainOverBaselinePercentagePoints']=100*(reachable.mean()-curves[str(f)]['baselineCompleteness'])
perface=[]
for f in sorted(set(faces)):
 dd=d[faces==f];perface.append({'triangle':int(f),'normal':n[f].tolist(),'samples':len(dd),'nearestPointDistance':ev.stats(dd),'unreachableAtOnePercentDiagonal':int(np.sum(dd>2*rho+.01*diag+slack))})
np.savetxt(OUT/'observable-sample-distances.csv',np.column_stack([np.flatnonzero(mask),faces,visible,indices,d]),delimiter=',',header='sample_index,reference_triangle,x,y,z,nearest_allpoint_id,nearest_allpoint_distance',comments='',fmt=['%d','%d','%.17g','%.17g','%.17g','%d','%.17g'])
result={'status':'EVALUATION_ONLY_COMPLETED','scope':'frozen observable sample set; oracle world frame; all indexed points including unknowns; no ICP, crop, scale fit or solver calls','allPoints':len(p),'allObservations':obs,'accumulatorSourceMemberships':memberships,'unknownNormalPoints':sum(int(r[1])==0 for r in rows),'totalReferenceSamples':len(q),'observableReferenceSamples':len(visible),'excludedUnobservableSamples':int((~mask).sum()),'referenceDiagonal':diag,'rho':rho,'maximumPermittedEdge':2*rho,'floatingComparisonSlack':slack,'nearestAllPointDistance':ev.stats(d),'curves':curves,'byReferenceTriangle':perface,'proof':'For triangle vertices a,b,c and x=lambda_a*a+lambda_b*b+lambda_c*c, distance(x,a)<=lambda_b*distance(b,a)+lambda_c*distance(c,a)<=2rho. If distance(q,mesh)<=epsilon, choosing a closest surface point x gives distance(q,P)<=distance(q,x)+distance(x,a)<=epsilon+2rho. Thus distance(q,P)>epsilon+2rho is impossible to cover within epsilon by any allowed triangle mesh using P. Using ALL points only weakens this bound. Reachability is necessary, not sufficient. Bound concerns sampled surface-distance completeness, NOT projected silhouette coverage.','inputs':pins,'scriptSHA256':sha(pathlib.Path(__file__))}
assert all(sha(paths[k])==rec['sha256'] for k,rec in pins.items())
result['inputsUnchangedAfterEvaluation']=True
result['sampleDistancesSHA256']=sha(OUT/'observable-sample-distances.csv')
(OUT/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
