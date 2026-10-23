"""Preserved independent-review counterexamples; successful output is forbidden."""
import json
from pathlib import Path
from run_controls import run
from chart_overlay import overlay
ROOT=Path(__file__).resolve().parent
report={}
for name in ['random','world-shift','in-plane']:
    data=json.loads((ROOT/'regressions'/f'{name}-input.json').read_text())
    status,value=run(data)
    expected='emitted exact world/chart support or provenance mismatch' if name=='in-plane' else 'emitted vertices leave exact observed world plane'
    if status!='refused' or value.get('exception')!='Refused' or value.get('reason')!=expected:raise AssertionError((name,status,value))
    report[name]={'status':status,'reason':value['reason']}
# A single polygon boundary is a LineString, not a MultiLineString.
data=json.loads((ROOT/'regressions/random-input.json').read_text());data['triangles']=data['triangles'][:1]
status,value=run(data)
if status=='error':raise AssertionError(('single_triangle',status,value))
report['single_triangle']={'status':status,'reason':value.get('reason'),'noGeometryCollectionAPIError':True}
(ROOT/'emitted-regressions.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
