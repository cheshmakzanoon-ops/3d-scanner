import json,tempfile
from pathlib import Path
from exact_support_audit import audit,ROOT
r={}
for name in ['complementary-forward','complementary-reverse','hole-forward','hole-reverse','dyadic-disjoint']:
    r[name]=audit(name,ROOT/'extended')
    if r[name]['lostSupportExact']!='0' or r[name]['addedSupportExact']!='0' or r[name]['overlapLinkMismatches'] or r[name]['lostWorldPlaneSupportExact']!='0' or r[name]['addedWorldPlaneSupportExact']!='0' or not r[name]['emittedVerticesExactlyOnSourcePlane']:raise AssertionError(name)
with tempfile.TemporaryDirectory(dir=ROOT) as scratch:
    p=Path(scratch);name='complementary';source=(ROOT/'results'/f'{name}-input.json').read_text();base=json.loads((ROOT/'results'/f'{name}-candidate.json').read_text());(p/f'{name}-input.json').write_text(source)
    altered=json.loads(json.dumps(base));altered['faceSourceTriangles'][0].pop();(p/f'{name}-candidate.json').write_text(json.dumps(altered));a=audit(name,p)
    if not a['overlapLinkMismatches']:raise AssertionError('missing link not caught')
    r['corrupt_link_detected']=True
    altered=json.loads(json.dumps(base));altered['vertices'][0][0]+=.01;(p/f'{name}-candidate.json').write_text(json.dumps(altered));a=audit(name,p)
    if a['lostSupportExact']=='0' and a['addedSupportExact']=='0':raise AssertionError('corrupt support not caught')
    r['corrupt_geometry_detected']=True
(ROOT/'additional-exact-checks.json').write_text(json.dumps(r,indent=2))
