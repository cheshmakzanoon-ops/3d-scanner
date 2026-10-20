import hashlib,json,os,subprocess,sys
from pathlib import Path
root=Path('/workspace/campaign/test004-benchmark');out=Path('/workspace/campaign/evidence/004/benchmark-followup');records=[]
for phase,path,coordinate,config,task in [('app-benchmark-lock-rejection','app/gradle.lockfile','androidx.core:core-ktx:1.16.0','benchmarkRuntimeClasspath',':app:checkBenchmarkAarMetadata'),('driver-benchmark-lock-rejection','macrobenchmark/gradle.lockfile','androidx.test.ext:junit:1.2.1','benchmarkRuntimeClasspath',':macrobenchmark:checkBenchmarkAarMetadata')]:
 p=root/path;original=p.read_bytes();changed=[];seen=False
 for line in original.decode().splitlines():
  if line.startswith(coordinate+'='):
   configs=line.split('=',1)[1].split(',');assert config in configs;configs.remove(config);seen=True
   if configs:changed.append(coordinate+'='+','.join(configs))
   changed.append(coordinate.rsplit(':',1)[0]+':0.0.0='+config)
  else:changed.append(line)
 assert seen
 try:
  p.write_text('\n'.join(changed)+'\n')
  result=subprocess.run([sys.executable,str(out/'run_phase.py'),phase,task],cwd=root)
  log=(out/(phase+'.log')).read_text();assert result.returncode!=0,phase+' unexpectedly passed'
  assert '0.0.0' in log and ('lock state' in log.lower() or 'strictly' in log.lower()),phase+' did not fail on lock constraint'
  records.append(dict(phase=phase,normal_no_init_rejected=True,returncode=result.returncode,original_sha256=hashlib.sha256(original).hexdigest()))
 finally:
  p.write_bytes(original);assert p.read_bytes()==original
(out/'ordinary-benchmark-lock-consumption.json').write_text(json.dumps(dict(schema=1,status='PASS',results=records,note='Disposable lock tampering only; both module locks restored exactly; no init script or verification bypass'),indent=2)+'\n');print(json.dumps(records,indent=2))
