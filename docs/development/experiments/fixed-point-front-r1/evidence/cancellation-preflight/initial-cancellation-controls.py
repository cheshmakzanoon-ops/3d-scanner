"""Own synthetic long-lived front worker: external INT/TERM must not publish."""
import os,signal,subprocess,time,pathlib,sys,json

def run(root):
 root.mkdir();source=pathlib.Path(__file__).resolve().parent;results={}
 for signum in (signal.SIGINT,signal.SIGTERM):
  case=root/str(signum);case.mkdir();pidfile=case/'worker.pid';success=case/'geometry-receipt.json';proc=None;pid=None
  child="""import sys,os,signal,pathlib
sys.dont_write_bytecode=True
sys.path.insert(0,SOURCE)
from front_controls import Analytic,patch,cameras
from exact_geometry import Budget
from front import reconstruct
signal.signal(signal.SIGTERM,signal.SIG_IGN)
pathlib.Path(PIDFILE).write_text(str(os.getpid()))
p,t=patch();cfg={'maxNeighborVisits':2000000,'maxProposalCombinationVisits':2000000,'maxProposals':2000000,'maxFrontVisits':2000000,'maxFaces':80000}
while True:reconstruct(Analytic(p,t,[(list(range(len(p))),cameras())],Budget()),cfg)
""".replace('SOURCE',repr(str(source))).replace('PIDFILE',repr(str(pidfile)))
  driver="""import sys,pathlib,json
sys.dont_write_bytecode=True
sys.path.insert(0,SOURCE)
from bounded import run
try:
 run(ARGV,LOG,30)
 pathlib.Path(SUCCESS).write_text('SHOULD_NOT_EXIST')
except BaseException as e:
 pathlib.Path(REFUSED).write_text(json.dumps({'status':'REFUSED','reason':str(e),'successfulGeometryPublication':False}))
 raise
""".replace('SOURCE',repr(str(source))).replace('ARGV',repr([sys.executable,'-B','-c',child])).replace('LOG',repr(str(case/'worker.log'))).replace('SUCCESS',repr(str(success))).replace('REFUSED',repr(str(case/'REFUSED.json')))
  try:
   with (case/'driver.log').open('xb') as log:
    mask=signal.pthread_sigmask(signal.SIG_BLOCK,(signal.SIGINT,signal.SIGTERM))
    try:proc=subprocess.Popen([sys.executable,'-B','-c',driver],stdout=log,stderr=log,start_new_session=True,preexec_fn=lambda:signal.pthread_sigmask(signal.SIG_SETMASK,mask))
    finally:signal.pthread_sigmask(signal.SIG_SETMASK,mask)
    deadline=time.monotonic()+10
    while not pidfile.exists() and time.monotonic()<deadline:time.sleep(.02)
    if not pidfile.exists():raise RuntimeError('cancellation worker not started')
    pid=int(pidfile.read_text());proc.send_signal(signum);proc.wait(timeout=8)
    try:os.kill(pid,0);alive=True
    except ProcessLookupError:alive=False
    if alive or success.exists() or proc.returncode==0 or not (case/'REFUSED.json').is_file():raise RuntimeError('cancellation publication/cleanup failure')
    results[str(signum)]={'driverExit':proc.returncode,'ownedWorkerGone':True,'successReceiptAbsent':True,'refusal':json.loads((case/'REFUSED.json').read_text())}
  finally:
   mask=signal.pthread_sigmask(signal.SIG_BLOCK,(signal.SIGINT,signal.SIGTERM))
   try:
    for group in (pid,proc.pid if proc else None):
     if group is not None:
      try:os.killpg(group,signal.SIGKILL)
      except ProcessLookupError:pass
    if proc:proc.wait(timeout=2)
   finally:signal.pthread_sigmask(signal.SIG_SETMASK,mask)
 return results
