"""Only run after inspecting strict generic outcome; all baselines remain synthetic/emulator-only."""
import datetime,fcntl,hashlib,json,pathlib,subprocess,time
base=pathlib.Path('/workspace/campaign/evidence/088/emulator-final-recovery')
source='86df647bcdba4b110ad36dfb6ce572adb4261462'
checkout=pathlib.Path('/workspace/campaign/frozen100')
app=pathlib.Path('/workspace/campaign/evidence/100/android-final/app-debug.apk')
test=pathlib.Path('/workspace/campaign/evidence/100/android-final/app-debug-androidTest.apk')
expected={app:'3b747cd8d568e8c64fad1993033089a30b006998337c5225d8db587d613c2f58',test:'4224aa71f0e3d4b7507a54092ca18fd20537b99d57bb3e3f3416ddea2ae4c523'}
base.mkdir(exist_ok=False)
for file,digest in expected.items():
    with file.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=checkout,text=True).strip()==source
receipt={'schema':1,'source_sha':source,'serial':'emulator-5580','app_apk_sha256':expected[app],'test_apk_sha256':expected[test],'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'physical_qualification':'NOT_RUN','installed_upgrade':'NOT_RUN','incoming006007023026080_product_acceptance':'NOT_RUN','stages':[]}
with open('/workspace/campaign/device-5580.lock','a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    with (base/'logcat.txt').open('w') as log:
        logger=subprocess.Popen(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580','logcat','-v','threadtime','TestRunner:I','AndroidRuntime:E','RealityForgeSessionTest:I','*:S'],stdout=log,stderr=subprocess.STDOUT)
        try:
            for stage in ('import','registration','mesh-texture','checkpoint','publication'):
                started=time.monotonic()
                command=['python','tools/device/failure_recovery/controller.py','--serial','emulator-5580','--source-sha',source,'--stage',stage,'--app-apk',str(app),'--test-apk',str(test),'--output',str(base/stage)]
                try:
                    result=subprocess.run(command,cwd=checkout,text=True,capture_output=True,timeout=1200)
                    (base/(stage+'.stdout.txt')).write_text(result.stdout);(base/(stage+'.stderr.txt')).write_text(result.stderr)
                    row={'stage':stage,'returncode':result.returncode,'duration_seconds':round(time.monotonic()-started,2)}
                    report=base/stage/'recovery.json'
                    row['controller']=json.loads(report.read_text()) if report.exists() else {'status':'FAIL','error':'No completed controller receipt'}
                except subprocess.TimeoutExpired as error:
                    (base/(stage+'.stdout.txt')).write_bytes(error.stdout or b'');(base/(stage+'.stderr.txt')).write_bytes(error.stderr or b'')
                    row={'stage':stage,'returncode':124,'duration_seconds':round(time.monotonic()-started,2),'controller':{'status':'FAIL','error':'Bounded1200s stage budget exceeded; no PASS inferred'}}
                    stopped=subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s','emulator-5580','shell','am','force-stop','com.realityforge.scanner'],text=True,capture_output=True,timeout=30)
                    (base/(stage+'.timeout-stop.txt')).write_text(stopped.stdout+stopped.stderr)
                receipt['stages'].append(row)
                (base/'execution.json').write_text(json.dumps(receipt,indent=2)+'\n')
                print(json.dumps({'stage':stage,'status':row['controller']['status'],'returncode':row['returncode'],'duration_seconds':row['duration_seconds']}),flush=True)
        finally:
            logger.terminate()
            try:logger.wait(timeout=10)
            except subprocess.TimeoutExpired:logger.kill();logger.wait(timeout=5)
            receipt['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            receipt['status']='PASS' if len(receipt['stages'])==5 and all(r['returncode']==0 and r['controller'].get('status')=='PASS' for r in receipt['stages']) else 'FAIL'
            (base/'execution.json').write_text(json.dumps(receipt,indent=2)+'\n')
            fcntl.flock(lock,fcntl.LOCK_UN)
