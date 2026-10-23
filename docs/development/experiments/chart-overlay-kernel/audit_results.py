from pathlib import Path
import json,hashlib,subprocess,sys
import numpy as np
from invariant_checks import verify_audit_report
ROOT=Path(__file__).resolve().parent;out=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'results';report={}
def geometry(d):
    vertices=np.array(d['vertices']);return sorted(tuple(sorted(tuple(float(x) for x in p) for p in vertices[t])) for t in d['triangles'])
for path in sorted(out.glob('*-candidate.json')):
    data=json.loads(path.read_text());name=path.name.removesuffix('-candidate.json');source=json.loads((out/f'{name}-input.json').read_text())
    text=f"{len(data['vertices'])} {len(data['triangles'])}\n"+'\n'.join(' '.join(map(str,p)) for p in data['vertices'])+'\n'+'\n'.join(' '.join(map(str,t)) for t in data['triangles'])+'\n';(out/f'{name}-topology.txt').write_text(text)
    result=subprocess.run([str(ROOT/'native_topology'),str(out/f'{name}-topology.txt')],capture_output=True,text=True,check=True)
    native=json.loads(result.stdout)
    ledger={o['id']:{k:v for k,v in o.items() if k not in ['status','supportRelation']} for o in data['observations']};original={o['id']:o for o in source['observations']}
    report[name]={'nativeTopology':native,'immutableSourceLedgerExactlyPreserved':ledger==original,'uniqueLedgerIds':len(ledger)==len(data['observations']),'candidateMetrics':data['metrics']}
left=json.loads((out/'translated-candidate.json').read_text());right=json.loads((out/'permuted-candidate.json').read_text());report['permutation']={'triangleCoordinateSetsExactlyEqual':geometry(left)==geometry(right)}
print(json.dumps(report,indent=2));(out/'independent-audit.json').write_text(json.dumps(report,indent=2)+'\n')

verify_audit_report(report)
