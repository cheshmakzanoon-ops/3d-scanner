import datetime,fcntl,json,pathlib,re,subprocess,time
base=pathlib.Path('/workspace/campaign/evidence/088/emulator-fresh5582-read-stall');base.mkdir(exist_ok=False)
r={'schema':1,'serial':'emulator-5582','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'requests':[],'commands':[]}
with open('/workspace/campaign/device-5582.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 def adb(name,*args,timeout=35):
  start=time.monotonic()
  try:
   value=subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5582',*args],capture_output=True,text=True,timeout=timeout);row={'name':name,'returncode':value.returncode};out=value.stdout;err=value.stderr
  except subprocess.TimeoutExpired as e:row={'name':name,'returncode':124};out=(e.stdout or b'').decode(errors='replace');err=(e.stderr or b'').decode(errors='replace')
  row['seconds']=round(time.monotonic()-start,2);(base/(name+'.stdout.txt')).write_text(out);(base/(name+'.stderr.txt')).write_text(err);r['commands'].append(row);(base/'receipt.json').write_text(json.dumps(r,indent=2)+'\n');return row,out
 row,out=adb('request-list','shell','run-as','com.realityforge.scanner.test','ls','files/qa-fault-documents')
 ids=out.strip().splitlines() if row['returncode']==0 else [];assert len(ids)<=16 and all(re.fullmatch('[A-Za-z0-9_-]{1,64}',value) for value in ids)
 for request in ids:
  row,out=adb('state-'+request,'shell','run-as','com.realityforge.scanner.test','cat','files/qa-fault-documents/'+request+'/state.json')
  if row['returncode']==0:r['requests'].append({'id':request,'state':json.loads(out)})
 adb('debuggerd-help','shell','debuggerd','--help')
 for package,name in [('com.realityforge.scanner','target'),('com.realityforge.scanner.test','provider')]:
  row,out=adb(name+'-pid','shell','pidof',package);pids=out.strip().split()
  if row['returncode']==0 and len(pids)==1 and pids[0].isdigit():
   adb(name+'-native-stacks','shell','run-as',package,'debuggerd','-b',pids[0],timeout=45)
   adb(name+'-java-stacks','shell','run-as',package,'debuggerd','-j',pids[0],timeout=45)
 adb('framework-watchdog','shell','dumpsys','dropbox','--print','system_server_watchdog',timeout=40)
 adb('current-logcat','logcat','-d','-t','600')
 adb('stop-target','shell','am','force-stop','com.realityforge.scanner')
 adb('stop-provider','shell','am','force-stop','com.realityforge.scanner.test')
r['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();(base/'receipt.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps({'requests':[{ 'id':row['id'],'last_events':row['state'].get('events',[])[-3:]} for row in r['requests']],'commands':r['commands'],'evidence':str(base)}),flush=True)
