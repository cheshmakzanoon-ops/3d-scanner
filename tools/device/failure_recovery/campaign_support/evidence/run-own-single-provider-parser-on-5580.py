import datetime,fcntl,hashlib,importlib.util,json,pathlib,subprocess,time
base=pathlib.Path('/workspace/campaign/evidence/088/emulator-single-parser-adcfb49');base.mkdir(exist_ok=False)
root=pathlib.Path('/workspace/agent-workspaces/088');source='adcfb4964b885c2c139eec7b96bf3a15d1997de6'
expected={'com.realityforge.scanner':'89a408045c51dc5a898268147e3713f5a11faa56b3d314dddfaec6f71905b8d9','com.realityforge.scanner.test':'fb88a007adfb3ffdcc6e24b11d8456ee3e2b04d037925327ec6be00c5d4085f5'}
selector='com.realityforge.scanner.qa.RecoveryStageContractTest#configuredPermissionFaultsAreDistinctFromMissingAndBounded'
spec=importlib.util.spec_from_file_location('protocol',root/'tools/device/failure_recovery/stdio_controller.py');protocol=importlib.util.module_from_spec(spec);spec.loader.exec_module(protocol)
receipt={'schema':1,'source_sha':source,'serial':'emulator-5580','status':'FAIL','selector':selector,'physical_qualification':'NOT_RUN','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'installed_apk_hashes':expected,'parser_sha256':hashlib.sha256((root/'tools/device/failure_recovery/stdio_controller.py').read_bytes()).hexdigest()}
with open('/workspace/campaign/device-5580.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 def adb(*parts,timeout=60):return subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580',*parts],capture_output=True,text=True,timeout=timeout)
 for package,sha in expected.items():
  location=adb('shell','pm','path',package);paths=location.stdout.strip().splitlines();assert location.returncode==0 and len(paths)==1 and paths[0].startswith('package:')
  actual=adb('shell','sha256sum',paths[0][8:]);assert actual.returncode==0 and actual.stdout.split()[0]==sha
 start=time.monotonic()
 try:
  result=adb('shell','am','instrument','-w','-r','-e','class',selector,'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner',timeout=180)
  (base/'instrumentation.txt').write_text(result.stdout);(base/'instrumentation.stderr.txt').write_text(result.stderr)
  receipt['returncode']=result.returncode;receipt['parsed']=protocol.parse_instrumentation(result.stdout,result.returncode,selector);receipt['status']='PASS'
 except subprocess.TimeoutExpired as error:
  (base/'instrumentation.txt').write_bytes(error.stdout or b'');(base/'instrumentation.stderr.txt').write_bytes(error.stderr or b'');receipt['error']='Bounded180s instrumentation timeout'
 except Exception as error:receipt['error']=str(error)
 finally:
  cleanup=adb('shell',"am broadcast --receiver-foreground -n 'com.realityforge.scanner.test/com.realityforge.scanner.qa.FaultDocumentProvider$GrantReceiver' --es operation revoke")
  (base/'grant-cleanup.txt').write_text(cleanup.stdout+cleanup.stderr)
  if cleanup.returncode or 'Broadcast completed: result=-1' not in cleanup.stdout:receipt.update(status='FAIL',cleanup_error='URI revocation failed')
  receipt.update(duration_seconds=round(time.monotonic()-start,2),finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
  (base/'execution.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)
