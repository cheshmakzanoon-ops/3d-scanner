import datetime,fcntl,hashlib,json,os,pathlib,re,subprocess,time
root=pathlib.Path('/workspace/campaign-061-080/077-device'); run=root/('late-install-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')); run.mkdir()
lock=open('/workspace/campaign-061-080/device.lock','a'); fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
(run/'lock.txt').write_text('Exclusive campaign device.lock held for full late-install verification/test attempt.\n')
records=[]; adb=['/workspace/campaign-061-080/adb','-s','emulator-5556']
env={k:v for k,v in os.environ.items() if k not in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']}
docker=['docker','--host=unix:///var/run/docker.sock']
def command(label,args,timeout=25):
    start=datetime.datetime.now(datetime.timezone.utc).isoformat(); t=time.monotonic()
    try:
        p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,env=env)
        code=p.returncode; out=p.stdout.decode(errors='replace'); expired=False
    except subprocess.TimeoutExpired as e: code=None; out=(e.stdout or b'').decode(errors='replace'); expired=True
    (run/(label+'.log')).write_text(out)
    item=dict(label=label,command=args,start_utc=start,duration_seconds=round(time.monotonic()-t,3),exit_code=code,timed_out=expired,log=label+'.log')
    records.append(item); (run/'commands.json').write_text(json.dumps(records,indent=2)+'\n'); print(json.dumps(item),flush=True)
    return code,out
def cleanup_client(expected_args,label):
    _,out=command(label+'-clients',docker+['top','rf-adb-server','-eo','pid,comm,args'],15)
    expected=['/workspace/toolchains/android-sdk/platform-tools/adb']+expected_args
    for line in out.splitlines()[1:]:
        parts=line.split(None,2)
        if len(parts)!=3 or not parts[0].isdigit(): continue
        pid=int(parts[0]); proc=pathlib.Path('/proc')/str(pid)
        try:
            args=proc.joinpath('cmdline').read_bytes().split(b'\0'); args=[a.decode() for a in args if a]
            if args!=expected: continue
            ns=next(x for x in proc.joinpath('status').read_text().splitlines() if x.startswith('NSpid:')).split()[-1]
        except FileNotFoundError: continue
        command(label+'-cleanup-'+str(pid),docker+['exec','rf-adb-server','/bin/sh','-c','kill -TERM '+ns],15)
        (run/(label+'-cleanup-identity.json')).write_text(json.dumps(dict(host_pid=pid,container_pid=int(ns),verified_exact_argv=args),indent=2)+'\n')
app_hash='5830c15c4fcd7675dcb03b8414c6937f6671550fdc22225966057cd541d5eb02'; test_hash='22bd5f6a68b06ca1e79d82d5c611b3d87821f4f9bf6ed537c71d42b31a6a82a2'
test=pathlib.Path('/workspace/agent-work/077/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
result=dict(status='NOT_RUN',device_gate='PENDING',build_source_sha='0c76ec2d2f9ab4dd3c7480036e9566b84307a515',serial='emulator-5556',expected_installed_app_sha256=app_hash,test_apk=dict(path=str(test),bytes=test.stat().st_size,sha256=hashlib.sha256(test.read_bytes()).hexdigest()),installed_app_hash_verified=False,test_apk_install='NOT_RUN',actual_accessibility_tests='NOT_RUN',physical_hardware=False,talkback_manual='NOT_RUN',earlier_attempt='../attempt-20261020T090718Z/RECEIPT.md')
assert result['test_apk']['sha256']==test_hash, 'Test APK source pair changed'
for key in ['ro.build.version.sdk','ro.product.cpu.abi','ro.build.fingerprint','sys.boot_completed']:
    command('prop-'+key,adb+['shell','getprop',key],20)
command('system-server-before',adb+['shell','pidof','system_server'],20)
command('crash-before',adb+['logcat','-b','crash','-d','-t','200'],25)
code,out=command('installed-app-path',adb+['shell','pm','path','com.realityforge.scanner'],35)
paths=[line[len('package:'):].strip() for line in out.splitlines() if line.startswith('package:')]
if code!=0 or len(paths)!=1 or not re.fullmatch(r'/data/app/[A-Za-z0-9._~+=/-]+/base\.apk',paths[0]):
    result.update(status='NOT_RUN_APP_IDENTITY_UNAVAILABLE',device_gate='BLOCKED_EXTERNAL')
else:
    path=paths[0]; result['installed_base_apk']=path
    code,out=command('installed-app-sha256',adb+['shell','sha256sum',path],90)
    actual=out.split()[0] if out.split() else None; result['observed_installed_app_sha256']=actual
    result['installed_app_hash_verified']=code==0 and actual==app_hash
    if not result['installed_app_hash_verified']:
        result.update(status='NOT_RUN_APP_IDENTITY_MISMATCH',device_gate='BLOCKED_IDENTITY')
    else:
        installargs=['-s','emulator-5556','install','--no-streaming','-r','-t',str(test)]
        code,out=command('test-apk-install',[adb[0]]+installargs,180)
        if code!=0 or 'Success' not in out:
            result.update(status='NOT_RUN_TEST_INSTALL_FAILED',device_gate='BLOCKED_EXTERNAL',test_apk_install='TIMED_OUT' if code is None else 'FAILED')
            if code is None: cleanup_client(installargs,'test-install')
        else:
            result['test_apk_install']='PASS'
            command('instrumentation-list',adb+['shell','pm','list','instrumentation'],25)
            testargs=['-s','emulator-5556','shell','am','instrument','-w','-r','-e','class','com.realityforge.scanner.agents.a077.ActualAccessibilityTest','com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner']
            code,out=command('actual-accessibility',[adb[0]]+testargs,360)
            if code==0 and re.search(r'OK \(3 tests\)',out): result.update(status='PASS_3_TESTS',device_gate='PASS_AUTOMATED_ACCESSIBILITY',actual_accessibility_tests='PASS (3 tests)')
            elif 'FAILURES!!!' in out or 'INSTRUMENTATION_STATUS_CODE: -2' in out: result.update(status='FAIL_ACTUAL_TESTS',device_gate='FAIL_AUTOMATED_ACCESSIBILITY',actual_accessibility_tests='FAIL')
            else: result.update(status='NOT_COMPLETED_INSTRUMENTATION',device_gate='BLOCKED_EXTERNAL',actual_accessibility_tests='INCOMPLETE')
            result['instrumentation_exit_code']=code
            print('ACTUAL_RESULT '+json.dumps(result),flush=True)
            (run/'result.json').write_text(json.dumps(result,indent=2)+'\n')
            if code is None:
                cleanup_client(testargs,'instrumentation-timeout')
                command('stop-timed-out-instrumentation',adb+['shell','am','force-stop','com.realityforge.scanner'],25)
            if result['status'] in ['FAIL_ACTUAL_TESTS','NOT_COMPLETED_INSTRUMENTATION']:
                command('screenshot',adb+['shell','screencap','-p','/data/local/tmp/agent077-late-install.png'],25)
                command('screenshot-pull',adb+['pull','/data/local/tmp/agent077-late-install.png',str(run/'screen.png')],25)
command('system-server-after',adb+['shell','pidof','system_server'],20)
command('crash-after',adb+['logcat','-b','crash','-d','-t','250'],25)
command('system-after',adb+['logcat','-b','system','-d','-t','600'],25)
command('clients-final',docker+['top','rf-adb-server','-eo','pid,comm,args'],15)
result['completed_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat(); result['device_lock']='Held exclusively through this attempt; released on script exit.'
(run/'result.json').write_text(json.dumps(result,indent=2)+'\n'); (root/'latest-late-install.txt').write_text(str(run)+'\n')
(run/'SHA256SUMS').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in sorted(run.iterdir()) if f.is_file() and f.name!='SHA256SUMS'))
print('FINAL_RESULT '+json.dumps(dict(directory=str(run),**result)),flush=True)
