import datetime,hashlib,json,os,subprocess,sys,time
from pathlib import Path
root=Path('/workspace/campaign/test004-benchmark');out=Path('/workspace/campaign/evidence/004/benchmark-followup')
phase=sys.argv[1];args=sys.argv[2:]
if not phase.replace('-','').isalnum():raise SystemExit('invalid phase')
cmd=['flock','/workspace/campaign/android.lock','python','/workspace/campaign/gradle.py','--dependency-verification','strict']+args
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None
files=['app/build.gradle.kts','settings.gradle.kts','app/gradle.lockfile','macrobenchmark/build.gradle.kts','macrobenchmark/gradle.lockfile','tools/dependencies/export.gradle','gradle/verification-metadata.xml']
before={s:sha(root/s) for s in files};start=time.monotonic();source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
with (out/(phase+'.log')).open('w') as log: result=subprocess.run(cmd,cwd=root,stdout=log,stderr=subprocess.STDOUT)
record=dict(schema=1,phase=phase,recorded_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),source_sha=source,command=cmd,returncode=result.returncode,seconds=round(time.monotonic()-start,3),log=str(out/(phase+'.log')),log_sha256=sha(out/(phase+'.log')),before=before,after={s:sha(root/s) for s in files})
(out/(phase+'.json')).write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({k:record[k] for k in ['phase','source_sha','returncode','seconds','log']}),flush=True);raise SystemExit(result.returncode)
