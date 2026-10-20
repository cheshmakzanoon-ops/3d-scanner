import datetime,fcntl,json,pathlib,subprocess,time
base=pathlib.Path('/workspace/campaign/evidence/092/emulator-reboot-after-watchdog');base.mkdir(exist_ok=False)
receipt={'schema':1,'status':'FAIL','serial':'emulator-5580','reason':'Restore unchanged isolated software AVD after actual system_server watchdog and180s install timeout','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'wipedata':False,'software_backend':'unchanged accel off/SwiftShader','physical_qualification':'NOT_RUN','probes':[]}
with open('/workspace/campaign/device-5580.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 def adb(*parts,timeout=20):
  try:
   r=subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580',*parts],capture_output=True,text=True,timeout=timeout)
   return {'returncode':r.returncode,'stdout':r.stdout,'stderr':r.stderr}
  except subprocess.TimeoutExpired as e:return {'returncode':124,'stdout':(e.stdout or b'').decode(errors='replace'),'stderr':(e.stderr or b'').decode(errors='replace'),'error':'bounded timeout'}
 try:
  receipt['prior_boot_id']=adb('shell','cat','/proc/sys/kernel/random/boot_id')
  for name,args in [('watchdog-dropbox',['shell','dumpsys','dropbox','--print','system_server_watchdog']),('system-logcat',['logcat','-d','-t','1200'])]:
   row=adb(*args,timeout=35);(base/(name+'.stdout.txt')).write_text(row.pop('stdout'));(base/(name+'.stderr.txt')).write_text(row.pop('stderr'));receipt[name]=row
  receipt['reboot_command']=adb('reboot',timeout=30)
  assert receipt['reboot_command']['returncode']==0
  start=time.monotonic()
  while time.monotonic()-start<420:
   row={'elapsed_seconds':round(time.monotonic()-start,2),'state':adb('get-state',timeout=8)}
   if row['state']['returncode']==0 and row['state']['stdout'].strip()=='device':
    row['boot_id']=adb('shell','cat','/proc/sys/kernel/random/boot_id',timeout=8)
    row['boot_completed']=adb('shell','getprop','sys.boot_completed',timeout=8)
    if row['boot_completed']['stdout'].strip()=='1' and row['boot_id']['stdout'].strip()!=receipt['prior_boot_id']['stdout'].strip():
     row['package_manager']=adb('shell','pm','path','android',timeout=15)
     if row['package_manager']['returncode']==0 and row['package_manager']['stdout'].startswith('package:'):
      receipt.update(status='BOOTED',boot_seconds=row['elapsed_seconds'],boot_id=row['boot_id']['stdout'].strip());receipt['probes'].append(row);break
   receipt['probes'].append(row);(base/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');time.sleep(8)
  if receipt['status']!='BOOTED':receipt['error']='Guest reboot did not establish newkernel+bootcompleted+package-manager readiness within420s'
 except Exception as error:receipt['error']=str(error)
 finally:
  receipt['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();(base/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({k:v for k,v in receipt.items() if k!='probes'}),flush=True)
