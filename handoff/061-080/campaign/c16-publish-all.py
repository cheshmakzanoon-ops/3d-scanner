import concurrent.futures,subprocess,sys,json
from pathlib import Path
roles=[f'{n:03}' for n in range(61,81) if n not in [67,77,80]]
def publish(role):
 p=subprocess.run([sys.executable,'/workspace/campaign-061-080/c16-publish.py',role],capture_output=True,text=True)
 if p.returncode:
  Path(f'/workspace/campaign-061-080/c16-delivery/{role}-failure.log').write_text(p.stdout+p.stderr)
  return {'agent':role,'state':'FAIL','exit_code':p.returncode,'error':p.stderr[-1200:]}
 result=json.loads(p.stdout.strip().splitlines()[-1]);return {'agent':role,'state':'PASS','head':result['head'],'remote_head':result['remote_head']}
failed=[]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 for future in concurrent.futures.as_completed([pool.submit(publish,r) for r in roles]):
  result=future.result();print(json.dumps(result),flush=True)
  if result['state']!='PASS':failed.append(result)
if failed:sys.exit(1)
print('ALL17 NORMAL PUSH AND REMOTE HEAD VERIFICATION PASS',flush=True)
