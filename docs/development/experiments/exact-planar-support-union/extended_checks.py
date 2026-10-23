import copy,json,math
from pathlib import Path
from make_controls import fixture
from run_controls import run
from original_overlay import overlay as old_overlay
from chart_overlay import overlay
from exact_support_audit import audit
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'extended';OUT.mkdir(exist_ok=True)
def geom(o):return sorted(tuple(sorted(tuple(o['vertices'][i]) for i in tri)) for tri in o['triangles'])
def ledger(o):return {r['id']:{k:v for k,v in r.items() if k!='supportRelation'} for r in o['observations']}
def links(o):
    return sorted((tuple(sorted(tuple(o['vertices'][i]) for i in face)),tuple(sorted(tuple(sorted(o['sourceTriangles'][s]['sources'])) for s in group))) for face,group in zip(o['triangles'],o['faceSourceTriangles']))
report={}
for name in ['complementary','hole','step','curved_identical']:
    data=fixture(name);reverse=copy.deepcopy(data)
    for k in ('cameras','observations','triangles'):reverse[k].reverse()
    sa,a=run(data);sb,b=run(reverse)
    assert sa==sb=='success';assert geom(a)==geom(b);assert ledger(a)==ledger(b);assert links(a)==links(b)
    report[name]={'bothStatuses':[sa,sb],'exactCoordinateSetPermutation':True,'exactImmutableLedgerPermutation':True,'exactSupportLinksPermutation':True,'native':a['metrics']['nativeTopology']}
    for suffix,d,o in [('forward',data,a),('reverse',reverse,b)]:
        (OUT/f'{name}-{suffix}-input.json').write_text(json.dumps(d));(OUT/f'{name}-{suffix}-candidate.json').write_text(json.dumps(o))
# Separate planar supports: no removal of observations or original controls.
d=fixture('complementary')
for o in d['observations']:
    if o['view']==1:o['xyz'][0]+=3
for c in d['cameras']:
    if c['id']==1:c['translation'][0]-=3
s,o=run(d)
if s!='refused' or o.get('reason')!='emitted exact world/chart support or provenance mismatch':raise AssertionError(('translated_disjoint',s,o))
report['translated_disjoint']={'status':s,'reason':o['reason'],'oldFixturePreserved':True}
(OUT/'translated-disjoint-input.json').write_text(json.dumps(d))
# Distinct exactly representable disconnected support, not a replacement of the
# preceding numerically difficult fixture: two dyadic squares at z=4.
d={'label':'synthetic dyadic disconnected planar patches','cameras':[{'id':0,'rotation':[1.,0,0,0,1.,0,0,0,1.],'translation':[0.,0,0]}],'observations':[],'triangles':[]}
for patch,x in enumerate([0.,2.]):
    ids=[]
    for k,p in enumerate([[x,0.,4.],[x+1.,0.,4.],[x+1.,1.,4.],[x,1.,4.]]):
        i=str(4*patch+k);ids.append(i);d['observations'].append({'id':i,'xyz':p,'view':0})
    d['triangles'] += [{'view':0,'sources':[ids[i] for i in tri]} for tri in [(0,1,2),(0,2,3)]]
s,o=run(d)
if s!='success' or o['metrics']['nativeTopology']['components']!=2:raise AssertionError(('dyadic_disjoint',s,o))
report['dyadic_disjoint']={'status':s,'metrics':o['metrics']}
(OUT/'dyadic-disjoint-input.json').write_text(json.dumps(d));(OUT/'dyadic-disjoint-candidate.json').write_text(json.dumps(o))
# A single 1-ULP off-plane observation must disable union simplification.
d={'label':'synthetic one-ULP nonplanar quad','cameras':[{'id':0,'rotation':[1.,0,0,0,1.,0,0,0,1.],'translation':[0.,0,0]}],'observations':[{'id':str(i),'view':0,'pixel':[i,0],'xyz':p,'confidence':.9,'supportingViews':2,'depth':p[2]} for i,p in enumerate([[0.,0.,5.],[1.,0.,5.],[0.,1.,5.],[1.,1.,math.nextafter(5.,6.)]])],'triangles':[{'view':0,'sources':['0','1','2']},{'view':0,'sources':['1','3','2']}]}
o=overlay(d);assert not o['metrics']['exactCoplanarRetriangulation'];report['one_ulp_nonplanar']={'exactCoplanarRetriangulation':False,'originalPathRetained':True}
(ROOT/'extended-checks.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
