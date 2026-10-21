"""Synthetic equality-gate tests only, never a reconstruction/quality fixture."""
from pathlib import Path
import argparse,importlib.util,json,sys
sys.dont_write_bytecode=True

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--work',required=True);a=ap.parse_args();root=Path(a.work);assert root.is_absolute() and root.parent.resolve()==root.parent and not root.exists();root.mkdir(mode=0o700)
 spec=importlib.util.spec_from_file_location('reviewed_compare',Path(__file__).with_name('compare.py'));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 original=root/'original';traced=root/'traced';live=root/'live'
 for d in [original,traced,live]:d.mkdir(mode=0o700)
 result={'meshPreview':{'schema':8,'vertices':[[0.0,0.0,1.0,12,34,56]],'triangles':[[0,0,0]],'intersections':0}}
 # Intentionally not a valid mesh; these bytes test equality gating only and never reach native geometry.
 baseline={'mesh.bits':b'GUARD_ONLY_RAW_FIELDS\x00','mesh.ply':b'GUARD_ONLY_PLY_BYTES\n','mesh.json':json.dumps(result['meshPreview']).encode()}
 counts=root/'counts.json';passed=[]
 def restore():
  for d in [original,traced,live]:
   for name,body in baseline.items():(d/name).write_bytes(body)
 def verify(value):return mod.verify_geometry_then_load_counts(original,traced,live/'mesh.bits',live/'mesh.ply',value,counts)
 restore();counts.write_text('{"testOnly":true}');assert verify(result)=={'testOnly':True};passed.append('positive-three-way-equality')
 for side in [original,traced,live]:
  for name in ['mesh.bits','mesh.ply','mesh.json']:
   restore();altered=json.loads(json.dumps(result))
   if side==live and name=='mesh.json':altered['meshPreview']['intersections']=1
   else:(side/name).write_bytes(baseline[name]+b'X')
   # Deliberately invalid counters: seeing a JSON parse error would prove they were read too early.
   counts.write_bytes(b'COUNTERS_MUST_NOT_BE_READ_ON_GEOMETRY_MISMATCH')
   try:verify(altered)
   except ValueError as error:assert 'mismatch' in str(error),str(error)
   else:raise AssertionError('counters admitted after '+side.name+'/'+name+' mismatch')
   passed.append(side.name+'-'+name+'-mismatch-blocks-counters')
 restore();counts.write_text('{"testOnly":true}');assert verify(result)=={'testOnly':True}
 receipt={'status':'SYNTHETIC_COMPARISON_GUARDS_PASS_NOT_GEOMETRY_QUALITY','guards':passed,'count':len(passed),'scope':'Actual comparison helper rejects original/traced/live raw, PLY or full JSON differences before reading counter bytes. Synthetic bytes never enter fuser.'};(root/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
if __name__=='__main__':main()
