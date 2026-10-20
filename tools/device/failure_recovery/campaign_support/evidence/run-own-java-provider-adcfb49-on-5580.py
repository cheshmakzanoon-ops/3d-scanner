import datetime,fcntl,hashlib,importlib.util,json,pathlib,shutil,subprocess,time
root=pathlib.Path('/workspace/agent-workspaces/088');source='adcfb4964b885c2c139eec7b96bf3a15d1997de6'
base=pathlib.Path('/workspace/campaign/evidence/088/emulator-java-provider-adcfb49');base.mkdir(exist_ok=False)
artifacts={'com.realityforge.scanner':(root/'app/build/outputs/apk/debug/app-debug.apk','89a408045c51dc5a898268147e3713f5a11faa56b3d314dddfaec6f71905b8d9'),'com.realityforge.scanner.test':(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','fb88a007adfb3ffdcc6e24b11d8456ee3e2b04d037925327ec6be00c5d4085f5')}
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()==source
for package,(path,sha) in artifacts.items():
 assert hashlib.sha256(path.read_bytes()).hexdigest()==sha
 shutil.copy2(path,base/path.name)
selector='com.realityforge.scanner.qa.RecoveryStageContractTest';excluded=selector+'#externalProcessBoundary'
required={selector+'#'+method for method in ['boundedProviderFaultsPreserveImportedOriginals','configuredDestinationBytesFaultsAndCleanupAreObservable','configuredPermissionFaultsAreDistinctFromMissingAndBounded']}
spec=importlib.util.spec_from_file_location('runner','/workspace/agent-workspaces/092/tools/device/compatibility/runner.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
receipt={'schema':1,'source_sha':source,'serial':'emulator-5580','status':'FAIL','qualification':'NOT_RUN','physical':'NOT_RUN','external_recovery':'NOT_RUN','required_actual_passes':sorted(required),'apk_hashes':{key:value[1] for key,value in artifacts.items()},'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
with open('/workspace/campaign/device-5580.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 def adb(*args,timeout=60):return subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580',*args],text=True,capture_output=True,timeout=timeout)
 for package,(path,sha) in artifacts.items():
  installed=adb('install','-r','-t',str(base/path.name),timeout=180)
  (base/(package+'.install.txt')).write_text(installed.stdout+installed.stderr);assert installed.returncode==0 and 'Success' in installed.stdout
  locations=adb('shell','pm','path',package);paths=locations.stdout.strip().splitlines();assert locations.returncode==0 and len(paths)==1 and paths[0].startswith('package:')
  actual=adb('shell','sha256sum',paths[0][8:]);assert actual.returncode==0 and actual.stdout.split()[0]==sha
 receipt['identity']={name:adb('shell','getprop',name).stdout.strip() for name in ['ro.build.fingerprint','ro.build.version.sdk','ro.product.cpu.abi','ro.kernel.qemu']}
 with (base/'logcat.txt').open('w') as log:
  logger=subprocess.Popen(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580','logcat','-T','1','-v','threadtime','TestRunner:I','AndroidRuntime:E','ActivityManager:I','*:S'],stdout=log,stderr=subprocess.STDOUT)
  started=time.monotonic()
  try:
   result=adb('shell','am','instrument','-w','-r','-e','class',selector,'-e','notClass',excluded,'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner',timeout=300)
   (base/'instrumentation.stdout.txt').write_text(result.stdout);(base/'instrumentation.stderr.txt').write_text(result.stderr);receipt['returncode']=result.returncode
   assert result.returncode==0
   parsed=runner._instrumentation_report(result.stdout)
   actual={event['class']+'#'+event['test'] for event in parsed['events']}
   if actual!=required or len(parsed['events'])!=3 or parsed['counts']['failed'] or parsed['counts']['skipped']:raise ValueError('All3 exact provider contracts must actually pass,0skips')
   receipt.update(status='PASS',counts=parsed['counts'])
  except subprocess.TimeoutExpired as error:
   (base/'instrumentation.stdout.txt').write_bytes(error.stdout or b'');(base/'instrumentation.stderr.txt').write_bytes(error.stderr or b'');receipt.update(returncode=124,error='Bounded300s provider instrumentation timeout')
   stop=adb('shell','am','force-stop','com.realityforge.scanner');(base/'timeout-stop.txt').write_text(stop.stdout+stop.stderr)
  except Exception as error:receipt['error']=str(error)
  finally:
   # Explicitly revoke synthetic URI grant even if instrumentation died before its finally.
   cleanup=adb('shell',"am broadcast --receiver-foreground -n 'com.realityforge.scanner.test/com.realityforge.scanner.qa.FaultDocumentProvider$GrantReceiver' --es operation revoke")
   (base/'grant-cleanup.txt').write_text(cleanup.stdout+cleanup.stderr)
   if cleanup.returncode or 'Broadcast completed: result=-1' not in cleanup.stdout:receipt.update(status='FAIL',cleanup_error='Test URI grant revocation failed')
   logger.terminate()
   try:logger.wait(timeout=10)
   except subprocess.TimeoutExpired:logger.kill();logger.wait(timeout=5)
   receipt.update(duration_seconds=round(time.monotonic()-started,2),finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
   (base/'execution.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)
   fcntl.flock(lock,fcntl.LOCK_UN)
