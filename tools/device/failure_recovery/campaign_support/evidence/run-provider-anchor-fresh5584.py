import datetime,fcntl,hashlib,importlib.util,json,pathlib,shutil,subprocess,time
root=pathlib.Path('/workspace/agent-workspaces/088');source='e1ccd5d77872107d078db04488526b00d7f9cf3b'
base=pathlib.Path('/workspace/campaign/evidence/088/emulator-java-provider-anchor-fresh5584');base.mkdir(exist_ok=False)
inputs={'com.realityforge.scanner':(root/'app/build/outputs/apk/debug/app-debug.apk','89a408045c51dc5a898268147e3713f5a11faa56b3d314dddfaec6f71905b8d9'),'com.realityforge.scanner.test':(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','54576293a2b5ff944d6a60aff5c54b4d9f3eb968dbf7e483f9af2b2028cdf92e')}
cls='com.realityforge.scanner.qa.RecoveryStageContractTest';methods={cls+'#'+name for name in ['boundedProviderFaultsPreserveImportedOriginals','configuredDestinationBytesFaultsAndCleanupAreObservable','configuredPermissionFaultsAreDistinctFromMissingAndBounded']};single=cls+'#configuredPermissionFaultsAreDistinctFromMissingAndBounded'
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
runner=module('runner','/workspace/agent-workspaces/092/tools/device/compatibility/runner.py');protocol=module('protocol',root/'tools/device/failure_recovery/stdio_controller.py')
receipt={'schema':1,'status':'FAIL','source_sha':source,'serial':'emulator-5584','physical_qualification':'NOT_RUN','full_generic42':'NO_PROMOTION','external_recovery':'NOT_RUN','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'groups':[],'packages':{}}
logger=None;log=None
with open('/workspace/campaign/device-5584.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 def adb(*args,timeout=45):return subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5584',*args],capture_output=True,text=True,timeout=timeout)
 def installed(package):
  row=adb('shell','pm','path',package);paths=row.stdout.strip().splitlines()
  if row.returncode or len(paths)!=1 or not paths[0].startswith('package:'):return None
  destination=base/(package+'.installed.apk')
  transfer=adb('pull',paths[0][8:],str(destination),timeout=180)
  (base/(package+'.installed-pull.txt')).write_text(transfer.stdout+transfer.stderr)
  if transfer.returncode:raise ValueError('Cannot pull complete installed APK bytes')
  return hashlib.sha256(destination.read_bytes()).hexdigest()

 try:
  boot_path=pathlib.Path('/workspace/campaign/evidence/092/emulator-5584/boot-ready.json');boot=json.loads(boot_path.read_text());assert boot['status']=='BOOTED',boot
  receipt['boot_evidence_sha256']=hashlib.sha256(boot_path.read_bytes()).hexdigest();fresh_boot=adb('shell','cat','/proc/sys/kernel/random/boot_id');assert fresh_boot.returncode==0;receipt['boot_id']=fresh_boot.stdout.strip();assert receipt['boot_id'];assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()==source
  for package,(path,sha) in inputs.items():
   assert hashlib.sha256(path.read_bytes()).hexdigest()==sha;shutil.copy2(path,base/path.name)
   prior=installed(package);row={'supplied_sha256':sha,'prior_installed_sha256':prior,'install_skipped_exact_hash':prior==sha}
   if prior!=sha:
    result=adb('install','-r','-t',str(base/path.name),timeout=180);(base/(package+'.install.txt')).write_text(result.stdout+result.stderr);assert result.returncode==0 and 'Success' in result.stdout
   row['installed_sha256']=installed(package);assert row['installed_sha256']==sha;receipt['packages'][package]=row
  receipt['identity']={key:adb('shell','getprop',key).stdout.strip() for key in ['ro.build.fingerprint','ro.build.version.sdk','ro.product.cpu.abi','ro.kernel.qemu']};receipt['page_size']=adb('shell','getconf','PAGESIZE').stdout.strip();assert receipt['page_size']=='4096';assert receipt['identity']['ro.kernel.qemu']=='1' and receipt['identity']['ro.build.version.sdk']=='29' and receipt['identity']['ro.product.cpu.abi']=='x86_64'
  log=(base/'logcat.txt').open('w');logger=subprocess.Popen(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5584','logcat','-T','1','-v','threadtime','TestRunner:I','AndroidRuntime:E','ActivityManager:I','*:S'],stdout=log,stderr=subprocess.STDOUT)
  for name,selector,expected,timeout in [('provider-all',cls,methods,300),('provider-single',single,{single},180)]:
   args=['shell','am','instrument','-w','-r','-e','class',selector]
   if name=='provider-all':args+=['-e','notClass',cls+'#externalProcessBoundary']
   args+=['com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner'];start=time.monotonic();row={'name':name,'selector':selector,'status':'FAIL','required_actual_passes':sorted(expected)}
   try:
    result=adb(*args,timeout=timeout);(base/(name+'.txt')).write_text(result.stdout);(base/(name+'.stderr.txt')).write_text(result.stderr);row['returncode']=result.returncode
    assert result.returncode==0;parsed=runner._instrumentation_report(result.stdout);actual={e['class']+'#'+e['test'] for e in parsed['events']}
    if actual!=expected or len(parsed['events'])!=len(expected) or parsed['counts']['failed'] or parsed['counts']['skipped']:raise ValueError('Exact methods must all pass with0skips')
    row.update(status='PASS',counts=parsed['counts'])
    if name=='provider-single':row['strict_shared_parser']=protocol.parse_instrumentation(result.stdout,result.returncode,single)
   except subprocess.TimeoutExpired as error:
    (base/(name+'.txt')).write_bytes(error.stdout or b'');(base/(name+'.stderr.txt')).write_bytes(error.stderr or b'');row['error']='Bounded instrumentation timeout';raise
   except Exception as error:row['error']=str(error);raise
   finally:
    row['duration_seconds']=round(time.monotonic()-start,2);receipt['groups'].append(row)
  receipt['status']='PASS'
 except Exception as error:
  receipt['error']=str(error)
  if isinstance(error,subprocess.TimeoutExpired):
   (base/'bootstrap-or-group-timeout.stdout.txt').write_bytes(error.stdout or b'');(base/'bootstrap-or-group-timeout.stderr.txt').write_bytes(error.stderr or b'')
 finally:
  try:
   cleanup=adb('shell',"am broadcast --include-stopped-packages --receiver-foreground -n 'com.realityforge.scanner.test/com.realityforge.scanner.qa.FaultDocumentProvider$GrantReceiver' --es operation revoke")
   (base/'grant-cleanup.txt').write_text(cleanup.stdout+cleanup.stderr)
   if cleanup.returncode or 'Broadcast completed: result=-1' not in cleanup.stdout or 'RF088_revoke' not in cleanup.stdout:receipt.update(status='FAIL',cleanup_error='Explicit test grant revocation failed')
  except Exception as error:receipt.update(status='FAIL',cleanup_error=str(error))
  if logger:
   logger.terminate()
   try:logger.wait(timeout=10)
   except subprocess.TimeoutExpired:logger.kill();logger.wait(timeout=5)
  if log:log.close()
  receipt['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();(base/'execution.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)
