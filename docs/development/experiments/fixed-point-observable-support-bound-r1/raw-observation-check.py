import pathlib,json,hashlib,numpy as np
from scipy.spatial import cKDTree
out=pathlib.Path(__file__).parent;r=json.loads((out/'receipt.json').read_text());path=pathlib.Path(r['inputs']['samples']['path']);before=hashlib.sha256(path.read_bytes()).hexdigest()
p=np.loadtxt(path)[:,3:6];assert p.shape==(9172,3)
q=np.loadtxt(out/'observable-sample-distances.csv',delimiter=',',skiprows=1)[:,2:5];d,_=cKDTree(p).query(q,eps=0,workers=1)
curves={}
for f in [0,0.005,0.01,0.02,0.05]:
 unreachable=int(sum(d>r['maximumPermittedEdge']+f*r['referenceDiagonal']+r['floatingComparisonSlack']));curves[str(f)]={'unreachableSamples':unreachable,'hypotheticalUpperBound':1-unreachable/len(q)}
assert hashlib.sha256(path.read_bytes()).hexdigest()==before
res={'status':'EVALUATION_ONLY','scope':'9172 raw verified observations, not legal fixed-point front vertices; unchanged fixed radius for descriptive fusion sensitivity comparison only; cannot combine point universes or alter solver','rawObservationCount':len(p),'samplesSHA256':before,'curves':curves}
(out/'raw-observation-check.json').write_text(json.dumps(res,indent=2)+'\n');print(json.dumps(res))
