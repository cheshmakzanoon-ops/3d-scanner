import pathlib,json,hashlib,numpy as np
from scipy.spatial import cKDTree
out=pathlib.Path(__file__).parent
r=json.loads((out/'receipt.json').read_text());a=pathlib.Path(r['inputs']['native_accumulators']['path']);rows=[line.split() for line in a.read_text().splitlines()];p=np.array([[float(x) for x in row[19:22]] for row in rows]);samples=np.loadtxt(out/'observable-sample-distances.csv',delimiter=',',skiprows=1)
d,idx=cKDTree(p).query(samples[:,2:5],k=1,eps=0,workers=1)
error=float(abs(d-samples[:,6]).max());assert error<1e-14
threshold=r['maximumPermittedEdge']+.01*r['referenceDiagonal'];gap=float(abs(d-threshold).min());assert gap>r['floatingComparisonSlack']*1000
check={'status':'PASS','independentAlgorithm':'scipy.spatial.cKDTree exact query vs chunked brute force numpy; independently parsed native accumulators','maxDistanceDifference':error,'closestSampleToOnePercentThreshold':gap,'unreachableAtOnePercent':int(sum(d>threshold)),'receiptSHA256':hashlib.sha256((out/'receipt.json').read_bytes()).hexdigest()}
(out/'independent-check.json').write_text(json.dumps(check,indent=2)+'\n');print(json.dumps(check))
