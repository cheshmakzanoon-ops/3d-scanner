import datetime,fcntl,json,pathlib,subprocess,hashlib
base=pathlib.Path('/workspace/campaign/evidence/088/emulator-post-reboot-bootstrap-diagnostic');base.mkdir(exist_ok=False)
r={'schema':1,'serial':'emulator-5580','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'commands':[]}
with open('/workspace/campaign/device-5580.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 def adb(name,*args,timeout=45):
  try:
   x=subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580',*args],capture_output=True,text=True,timeout=timeout);row={'name':name,'returncode':x.returncode};out=x.stdout;err=x.stderr
  except subprocess.TimeoutExpired as e:row={'name':name,'returncode':124};out=(e.stdout or b'').decode(errors='replace');err=(e.stderr or b'').decode(errors='replace')
  (base/(name+'.stdout.txt')).write_text(out);(base/(name+'.stderr.txt')).write_text(err);r['commands'].append(row);return row,out
 adb('test-package-state','shell','dumpsys','package','com.realityforge.scanner.test')
 adb('fresh-boot-id','shell','cat','/proc/sys/kernel/random/boot_id')
 for package,name in [('com.realityforge.scanner','app'),('com.realityforge.scanner.test','test')]:
  row,out=adb(name+'-path','shell','pm','path',package)
  lines=out.strip().splitlines()
  if row['returncode']==0 and len(lines)==1 and lines[0].startswith('package:'):
   dest=base/(name+'-installed.apk');row,_=adb(name+'-pull','pull',lines[0][8:],str(dest),timeout=180)
   if row['returncode']==0:r[name+'_installed_sha256']=hashlib.sha256(dest.read_bytes()).hexdigest()
 adb('explicit-include-stopped-revoke','shell',"am broadcast --include-stopped-packages --receiver-foreground -n 'com.realityforge.scanner.test/com.realityforge.scanner.qa.FaultDocumentProvider$GrantReceiver' --es operation revoke")
r['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();(base/'receipt.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r),flush=True)
