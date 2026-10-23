import pathlib,sys,json
sys.dont_write_bytecode=True
sys.path.insert(0,'/workspace/shared/global-connectivity-adapter-recovered-r3/source')
from adapter_controls import make_case
from ledger_adapter import *
root=pathlib.Path('/workspace/shared/global-connectivity-r3-independent-review');result={}
for mode in ['pixel_mismatch','invalid_flag','out_of_bounds_support']:
 pins=make_case(root/mode,2)
 def change(key,fn):
  p=pathlib.Path(pins[key]['path']);ls=p.read_text().splitlines();fn(ls);p.write_text('\n'.join(ls)+'\n');pins[key].update(bytes=p.stat().st_size,sha256=sha(p))
 if mode=='pixel_mismatch':
  def a(ls):
   q=ls[0].split();q[1]=str(int(q[1])+5);ls[0]=' '.join(q)
  def b(ls):
   q=ls[1].split();q[2]=str(int(q[2])+5);ls[1]=' '.join(q)
  change('samples',a);change('prepared',b)
 elif mode=='invalid_flag':
  def a(ls):
   q=ls[4].split();q[ls[0].split().index('preparedSupported')]='2';ls[4]=' '.join(q)
  change('prepared',a)
 else:
  def a(ls):
   q=ls[1].split();q[-1]='9999999';ls[1]=' '.join(q)
  change('prepared',a)
 try:Ledger(pins,Budget(),F(1,4));result[mode]='ACCEPTED'
 except Exception as e:result[mode]=str(e)
print(json.dumps(result,indent=2))
