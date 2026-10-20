"""One bounded supplementary pass using a previously verified installed APK pair."""
from pathlib import Path
import argparse,datetime,hashlib,importlib.util,json,subprocess,time

def main():
 parser=argparse.ArgumentParser()
 parser.add_argument('--installed-from',required=True);parser.add_argument('--output',required=True)
 args=parser.parse_args()
 previous=Path(args.installed_from).resolve(strict=True)
 identity=json.loads((previous/'artifacts.json').read_text())
 assert identity['qualification'] in ['PRE_FIX_CHECKPOINT','FINAL_CANDIDATE']
 for i,artifact in enumerate(identity['apk_artifacts']):
  assert hashlib.sha256(Path(artifact['path']).read_bytes()).hexdigest()==artifact['sha256']
  assert 'Success' in (previous/f'install-{i}.log').read_text()
 assert (previous/'summary.json').is_file(),'Do not interrupt the current instrumentation batches'
 root=Path(args.output);root.mkdir(parents=True,exist_ok=False)
 classes=['com.realityforge.scanner.engine.imaging.PyramidRectifierTest',
 'com.realityforge.scanner.NativeSmokeTest#rectifiedCpuDenseStereoReconstructsActualPixelsThroughJni']
 command=['adb','-s',identity['device'],'shell','am','instrument','-w','-r','-e','class',','.join(classes),
 'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner']
 record={**identity,'installed_from':str(previous),'classes':classes,'command':command,
 'timeout_seconds':1800,'utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 (root/'artifacts.json').write_text(json.dumps(record,indent=2)+'\n')
 path=root/'instrumentation.log';start=time.monotonic();timed_out=False
 print(json.dumps({'phase':'test_start','qualification':identity['qualification'],'source_sha':identity['source_sha'],'classes':classes}),flush=True)
 with path.open('w') as log:
  process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
  try:code=process.wait(timeout=1800)
  except subprocess.TimeoutExpired:
   timed_out=True;process.kill();code=process.wait()
 spec=importlib.util.spec_from_file_location('frozen_runner',Path(__file__).with_name('run_frozen.py'))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 result=module.result(path.read_text(errors='replace'),code,timed_out)
 result.update({'qualification':identity['qualification'],'source_sha':identity['source_sha'],
 'seconds':round(time.monotonic()-start,2),'exit':code,'log':str(path),
 'log_sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
 (root/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
 for name,args in [('logcat',['logcat','-d','-t','2000'])]+([('timeout-stop',['shell','am','force-stop','com.realityforge.scanner'])] if timed_out else []):
  try:
   r=subprocess.run(['adb','-s',identity['device'],*args],capture_output=True,timeout=30)
   (root/f'{name}.log').write_bytes(r.stdout+r.stderr)
  except subprocess.TimeoutExpired:pass
 return 0 if result['classification']=='PASS' else 1

if __name__=='__main__':raise SystemExit(main())
