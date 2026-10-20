from pathlib import Path
import fcntl,subprocess,json,hashlib,sys,os
root=Path('/workspace/campaign-061-080/studio-combined')
base=Path('/workspace/campaign-061-080/filament')
snapshot=json.loads((base/'070-source-inputs-v4.json').read_text())
with Path('/workspace/campaign-061-080/android.lock').open('a') as lock:
 fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
 for p,h in snapshot['files'].items():assert hashlib.sha256((root/p).read_bytes()).hexdigest()==h,p
 with (base/'070-full-v4.log').open('w') as output:
  output.write(json.dumps({'cwd':str(root),'source_digest_sha256':snapshot['source_digest_sha256'],'heads':snapshot['heads']})+'\n');output.flush()
  command=[sys.executable,'/workspace/toolchains/gradle-cloud.py','--init-script',str(base/'dependencies.init.gradle'),':app:assembleDebug',':app:assembleDebugAndroidTest',':app:testDebugUnitTest',':app:lintDebug']
  result=subprocess.run(command,cwd=root,stdout=output,stderr=subprocess.STDOUT)
 record={'cwd':str(root),'source_digest_sha256':snapshot['source_digest_sha256'],'heads':snapshot['heads'],'command':command,'exit_code':result.returncode}
 for p,h in snapshot['files'].items():assert hashlib.sha256((root/p).read_bytes()).hexdigest()==h,p
 for name in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
  file=root/name
  if file.exists():record[name]=hashlib.sha256(file.read_bytes()).hexdigest()
 (base/'070-full-v4-result.json').write_text(json.dumps(record,indent=2,sort_keys=True))
 print(json.dumps(record));sys.exit(result.returncode)
