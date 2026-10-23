import argparse,contextlib,hashlib,importlib.util,threading,tempfile,traceback,json,os
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);source=parser.parse_args().source
assert hashlib.sha256(source.read_bytes()).hexdigest()=='df8ee01799b85d896a11a3cc34c79393a7930b59a4534bc37a97d56f040d8f50','Original c85 runtime required'
spec=importlib.util.spec_from_file_location('original_inventory',source);r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
with tempfile.TemporaryDirectory(prefix='eight-race-baseline-') as d:
 root=Path(d);queued=root/'queued';queued.mkdir();ready=threading.Event();done=threading.Event();original=r.os.scandir
 def remove():
  assert ready.wait(5);queued.rmdir();done.set()
 @contextlib.contextmanager
 def scan(path):
  with original(path) as entries:yield entries
  if Path(path)==root:ready.set();assert done.wait(5)
 t=threading.Thread(target=remove);t.start();r.os.scandir=scan
 try:
  try:r.inventory(root)
  except FileNotFoundError:
   print('REPRODUCED actual concurrent descendant deletion; original inventory fails:');traceback.print_exc()
  else:raise AssertionError('Expected baseline failure')
 finally:r.os.scandir=original;t.join(5);assert not t.is_alive()
