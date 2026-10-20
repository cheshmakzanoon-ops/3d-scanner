import datetime,fcntl,hashlib,importlib.util,json,pathlib,subprocess,time
checkout=pathlib.Path('/workspace/campaign/frozen100')
base=pathlib.Path('/workspace/campaign/evidence/092/emulator-final-supplemental')
source='86df647bcdba4b110ad36dfb6ce572adb4261462'
package='com.realityforge.scanner'
expected={'com.realityforge.scanner':'3b747cd8d568e8c64fad1993033089a30b006998337c5225d8db587d613c2f58','com.realityforge.scanner.test':'4224aa71f0e3d4b7507a54092ca18fd20537b99d57bb3e3f3416ddea2ae4c523'}
spec=importlib.util.spec_from_file_location('runner',checkout/'tools/device/compatibility/runner.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
base.mkdir(exist_ok=False)
selected=[('jni-boundary', ['com.realityforge.scanner.qa.FuzzBridgeContractTest','com.realityforge.scanner.qa.CompatibilityContractTest'],None,{
'com.realityforge.scanner.qa.FuzzBridgeContractTest#mismatchedLengthsAreRejectedRepeatedlyBeforeNativeReads',
'com.realityforge.scanner.qa.FuzzBridgeContractTest#nonfiniteArgumentsProduceSupportedErrorsAndNoOutput',
'com.realityforge.scanner.qa.FuzzBridgeContractTest#cancellationAndFinishRacesNeverRecycleTheHandle',
'com.realityforge.scanner.qa.CompatibilityContractTest#installedNativeLibrariesRunOnMeasuredPageSize'}),
('provider-contract',['com.realityforge.scanner.qa.RecoveryStageContractTest'],'com.realityforge.scanner.qa.RecoveryStageContractTest#externalProcessBoundary',{
'com.realityforge.scanner.qa.RecoveryStageContractTest#boundedProviderFaultsPreserveImportedOriginals',
'com.realityforge.scanner.qa.RecoveryStageContractTest#configuredDestinationBytesFaultsAndCleanupAreObservable',
'com.realityforge.scanner.qa.RecoveryStageContractTest#configuredPermissionFaultsAreDistinctFromMissingAndBounded'})]
receipt={'schema':1,'source_sha':source,'serial':'emulator-5580','status':'FAIL','qualification':'NOT_RUN','full42debugscope':'NO_PROMOTION; these are independent supplemental small contracts','specialist_gates':'NOT_RUN','physical_gates':'NOT_RUN','groups':[]}
with open('/workspace/campaign/device-5580.lock','a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    def adb(*args,timeout=40):
        return subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580',*args],text=True,capture_output=True,timeout=timeout)
    receipt['started_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    for target,sha in expected.items():
        installed=adb('shell','pm','path',target)
        paths=installed.stdout.strip().splitlines();assert installed.returncode==0 and len(paths)==1 and paths[0].startswith('package:')
        actual=adb('shell','sha256sum',paths[0][8:]);assert actual.returncode==0 and actual.stdout.split()[0]==sha
    receipt['installed_apk_hashes']=expected
    stopped=adb('shell','am','force-stop',package)
    (base/'previous-timed-out-scope-stop.txt').write_text(stopped.stdout+stopped.stderr)
    assert stopped.returncode==0
    receipt['identity']={key:adb('shell','getprop',key).stdout.strip() for key in ['ro.build.fingerprint','ro.build.version.sdk','ro.product.cpu.abi','ro.kernel.qemu']}
    with (base/'logcat.txt').open('w') as log:
        logger=subprocess.Popen(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580','logcat','-v','threadtime','TestRunner:I','AndroidRuntime:E','RealityForgeCompatibility:I','*:S'],stdout=log,stderr=subprocess.STDOUT)
        try:
            for name,classes,excluded,methods in selected:
                command=['shell','am','instrument','-w','-r','-e','class',','.join(classes)]
                if excluded:command+=['-e','notClass',excluded]
                command+=[package+'.test/androidx.test.runner.AndroidJUnitRunner']
                started=time.monotonic();row={'name':name,'status':'FAIL','classes':classes,'excluded':excluded,'required_actual_passes':sorted(methods),'timeout_seconds':240}
                try:
                    result=adb(*command,timeout=240)
                    (base/(name+'.stdout.txt')).write_text(result.stdout);(base/(name+'.stderr.txt')).write_text(result.stderr)
                    row['returncode']=result.returncode
                    if result.returncode:raise ValueError('Instrumentation command returned nonzero')
                    parsed=runner._instrumentation_report(result.stdout)
                    actual={event['class']+'#'+event['test'] for event in parsed['events']}
                    if actual!=methods or len(parsed['events'])!=len(methods) or parsed['counts']['skipped'] or parsed['counts']['failed']:
                        raise ValueError('Exact supplemental methods must all actually pass; no skip/extra/wrong identity')
                    row.update(status='PASS',counts=parsed['counts'])
                except subprocess.TimeoutExpired as error:
                    (base/(name+'.stdout.txt')).write_bytes(error.stdout or b'');(base/(name+'.stderr.txt')).write_bytes(error.stderr or b'')
                    row.update(returncode=124,error='Bounded240s supplemental instrumentation timeout; no PASS')
                    stopped=adb('shell','am','force-stop',package);(base/(name+'.timeout-stop.txt')).write_text(stopped.stdout+stopped.stderr)
                except (ValueError,RuntimeError) as error:row['error']=str(error)
                row['duration_seconds']=round(time.monotonic()-started,2);receipt['groups'].append(row)
                (base/'execution.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(row),flush=True)
        finally:
            logger.terminate()
            try:logger.wait(timeout=10)
            except subprocess.TimeoutExpired:logger.kill();logger.wait(timeout=5)
            receipt['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            receipt['status']='PASS' if len(receipt['groups'])==2 and all(row['status']=='PASS' for row in receipt['groups']) else 'FAIL'
            (base/'execution.json').write_text(json.dumps(receipt,indent=2)+'\n')
            fcntl.flock(lock,fcntl.LOCK_UN)
