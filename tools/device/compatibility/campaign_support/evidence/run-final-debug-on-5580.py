import datetime,fcntl,hashlib,json,pathlib,subprocess,time
base=pathlib.Path('/workspace/campaign/evidence/092/emulator-final-debug')
base.mkdir(exist_ok=False)
source='86df647bcdba4b110ad36dfb6ce572adb4261462'
checkout=pathlib.Path('/workspace/campaign/frozen100')
app=pathlib.Path('/workspace/campaign/evidence/100/android-final/app-debug.apk')
test=pathlib.Path('/workspace/campaign/evidence/100/android-final/app-debug-androidTest.apk')
expected={app:'3b747cd8d568e8c64fad1993033089a30b006998337c5225d8db587d613c2f58',test:'4224aa71f0e3d4b7507a54092ca18fd20537b99d57bb3e3f3416ddea2ae4c523'}
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=checkout,text=True).strip()==source
assert not subprocess.check_output(['git','status','--porcelain'],cwd=checkout,text=True).strip()
summary=json.loads(pathlib.Path('/workspace/campaign/evidence/100/android-final/summary.json').read_text())
assert summary['status']=='PASS' and summary['source_sha']==source
for file,digest in expected.items():
    with file.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest
candidate={'schema':1,'source_sha':source,'checkout':str(checkout),'serial':'emulator-5580','scope':'debug','app_apk_sha256':expected[app],'test_apk_sha256':expected[test],'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'physical_qualification':'NOT_RUN','specialist_qualification':'NOT_RUN','suite_manifest_sha256':hashlib.sha256((checkout/'tools/device/compatibility/suites.json').read_bytes()).hexdigest(),'runner_sha256':hashlib.sha256((checkout/'tools/device/compatibility/runner.py').read_bytes()).hexdigest()}
(base/'candidate.json').write_text(json.dumps(candidate,indent=2)+'\n')
args=['bash','tools/run-device-tests.sh','--serial','emulator-5580','--scope','debug','--app-apk',str(app),'--test-apk',str(test),'--expect-api','29','--expect-abi','x86_64','--expect-page-size','4096','--output',str(base/'compatibility')]
with open('/workspace/campaign/device-5580.lock','a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    ready=json.loads(pathlib.Path('/workspace/campaign/evidence/092/emulator-runtime/boot-receipt.json').read_text())
    assert ready['status']=='BOOTED',ready
    candidate['emulator_boot']=ready
    candidate['device_interval_acquired_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    (base/'candidate.json').write_text(json.dumps(candidate,indent=2)+'\n')
    log=open(base/'logcat.txt','w')
    logger=subprocess.Popen(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580','logcat','-v','threadtime','TestRunner:I','AndroidRuntime:E','ActivityManager:I','RealityForgeCompatibility:I','RealityForgeSessionTest:I','*:S'],stdout=log,stderr=subprocess.STDOUT)
    started=time.monotonic()
    try:
        try:
            result=subprocess.run(args,cwd=checkout,text=True,capture_output=True,timeout=1500)
            (base/'runner.stdout.txt').write_text(result.stdout);(base/'runner.stderr.txt').write_text(result.stderr)
            candidate.update(returncode=result.returncode,duration_seconds=round(time.monotonic()-started,2))
        except subprocess.TimeoutExpired as error:
            (base/'runner.stdout.txt').write_bytes(error.stdout or b'');(base/'runner.stderr.txt').write_bytes(error.stderr or b'')
            candidate.update(returncode=124,duration_seconds=round(time.monotonic()-started,2),error='Strict generic compatibility exceeded bounded1500s overall budget; no PASS inferred')
            cleanup=subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580','shell','am','force-stop','com.realityforge.scanner'],capture_output=True,text=True,timeout=30)
            (base/'timeout-stop.txt').write_text(cleanup.stdout+cleanup.stderr)
    finally:
        logger.terminate()
        try:logger.wait(timeout=10)
        except subprocess.TimeoutExpired:logger.kill();logger.wait(timeout=5)
        log.close()
        candidate['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        candidate['scope_status']=json.loads((base/'compatibility/compatibility.json').read_text()).get('status','FAIL') if (base/'compatibility/compatibility.json').exists() else 'FAIL'
        (base/'execution.json').write_text(json.dumps(candidate,indent=2)+'\n')
        print(json.dumps({'scope_status':candidate['scope_status'],'returncode':candidate.get('returncode'),'duration_seconds':candidate.get('duration_seconds'),'evidence':str(base)}),flush=True)
        fcntl.flock(lock,fcntl.LOCK_UN)
