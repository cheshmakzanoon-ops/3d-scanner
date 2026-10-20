import datetime,fcntl,json,pathlib,re,subprocess
base=pathlib.Path('/workspace/campaign/evidence/088/emulator-java-provider-adcfb49')
receipt={'observed_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Only explicitly synthetic test-APK private provider fixtures','requests':[]}
with open('/workspace/campaign/device-5580.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 def adb(*args):return subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580',*args],capture_output=True,text=True,timeout=45)
 listing=adb('shell','run-as','com.realityforge.scanner.test','ls','files/qa-fault-documents')
 receipt['listing']={'returncode':listing.returncode,'stdout':listing.stdout,'stderr':listing.stderr}
 ids=listing.stdout.strip().splitlines() if listing.returncode==0 else []
 assert len(ids)<=16 and all(re.fullmatch('[A-Za-z0-9_-]{1,64}',value) for value in ids)
 for request in ids:
  state=adb('shell','run-as','com.realityforge.scanner.test','cat','files/qa-fault-documents/'+request+'/state.json')
  row={'request':request,'returncode':state.returncode,'stderr':state.stderr}
  if state.returncode==0:row['state']=json.loads(state.stdout)
  receipt['requests'].append(row)
 stopped=adb('shell','am','force-stop','com.realityforge.scanner.test')
 receipt['provider_stop']={'returncode':stopped.returncode,'stdout':stopped.stdout,'stderr':stopped.stderr}
(base/'post-timeout-provider-state.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'requests':len(ids),'last_events':[[row['request'],row.get('state',{}).get('events',[])[-1:]] for row in receipt['requests']],'provider_stopped':stopped.returncode==0}),flush=True)
