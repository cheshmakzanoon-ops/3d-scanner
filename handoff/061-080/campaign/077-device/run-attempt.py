import datetime, fcntl, hashlib, json, pathlib, subprocess, time
root=pathlib.Path('/workspace/campaign-061-080/077-device')
run=root/('attempt-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
run.mkdir()
records=[]
serial='emulator-5556'
adb=['/workspace/campaign-061-080/adb','-s',serial]
lock=open('/workspace/campaign-061-080/device.lock','a')
try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError: raise SystemExit('device.lock is busy; no device operations performed')
(run/'lock.txt').write_text('Exclusive fcntl.flock device.lock held for full attempt.\n')
def command(label,args,timeout=20):
    start=datetime.datetime.now(datetime.timezone.utc).isoformat()
    t=time.monotonic()
    try:
        p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
        code=p.returncode; out=p.stdout.decode(errors='replace'); expired=False
    except subprocess.TimeoutExpired as e:
        code=None; out=(e.stdout or b'').decode(errors='replace'); expired=True
    (run/(label+'.log')).write_text(out)
    item=dict(label=label,command=args,start_utc=start,duration_seconds=round(time.monotonic()-t,3),exit_code=code,timed_out=expired,log=label+'.log')
    records.append(item)
    (run/'commands.json').write_text(json.dumps(records,indent=2)+'\n')
    print(json.dumps(item),flush=True)
    return code,out
apks=[pathlib.Path('/workspace/agent-work/077/app/build/outputs/apk/debug/app-debug.apk'),pathlib.Path('/workspace/agent-work/077/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]
manifest=dict(build_source_sha='0c76ec2d2f9ab4dd3c7480036e9566b84307a515',build_source_authority='Parent-supplied immutable Agent077 APK-pair source',serial=serial,apks=[dict(path=str(p),bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),mtime_utc=datetime.datetime.fromtimestamp(p.stat().st_mtime,datetime.timezone.utc).isoformat()) for p in apks])
manifest['observed_delivery_head']=subprocess.check_output(['git','-C','/workspace/agent-work/077','rev-parse','HEAD'],text=True).strip()
(run/'identity.json').write_text(json.dumps(manifest,indent=2)+'\n')
command('app-badging',['/workspace/toolchains/android-sdk/build-tools/36.0.0/aapt2','dump','badging',str(apks[0])])
command('test-manifest',['/workspace/toolchains/android-sdk/build-tools/36.0.0/aapt2','dump','xmltree','--file','AndroidManifest.xml',str(apks[1])])
command('devices',['/workspace/campaign-061-080/adb','devices','-l'])
for key in ['ro.build.version.sdk','ro.product.cpu.abi','ro.build.fingerprint','ro.kernel.qemu','sys.boot_completed','init.svc.bootanim']:
    command('prop-'+key,adb+['shell','getprop',key],15)
command('uptime',adb+['shell','cat','/proc/uptime'])
command('crash-before',adb+['logcat','-b','crash','-d','-t','200'],20)
ready=False
lastpid=None
for i in range(3):
    _,pid=command('health-'+str(i)+'-pid',adb+['shell','pidof','system_server'],12)
    _,package=command('health-'+str(i)+'-package',adb+['shell','service','check','package'],12)
    _,activity=command('health-'+str(i)+'-activity',adb+['shell','service','check','activity'],12)
    _,boot=command('health-'+str(i)+'-boot',adb+['shell','getprop','sys.boot_completed'],12)
    pid=pid.strip()
    if pid and pid==lastpid and 'Service package: found' in package and 'Service activity: found' in activity:
        ready=True; break
    lastpid=pid
    if i<2: time.sleep(10)
status='NOT_RUN_GUEST_UNSTABLE'
if ready:
    status='NOT_RUN_INSTALL_FAILED'
    ok=True
    for i,p in enumerate(apks):
        code,out=command('install-'+str(i),adb+['install','--no-streaming','-r','-t',str(p)],180)
        if code!=0 or 'Success' not in out:
            ok=False; break
    if ok:
        command('instrumentation-list',adb+['shell','pm','list','instrumentation'])
        status='ATTEMPTED'
        code,out=command('actual-accessibility',adb+['shell','am','instrument','-w','-r','-e','class','com.realityforge.scanner.agents.a077.ActualAccessibilityTest','com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner'],300)
        if code==0 and 'OK (3 tests)' in out: status='PASS_3_TESTS'
        elif 'FAILURES!!!' in out or 'INSTRUMENTATION_STATUS_CODE: -2' in out: status='FAIL_ACTUAL_TESTS'
        else: status='NOT_COMPLETED_INSTRUMENTATION'
command('final-boot',adb+['shell','getprop','sys.boot_completed'],12)
command('final-pid',adb+['shell','pidof','system_server'],12)
command('crash-after',adb+['logcat','-b','crash','-d','-t','250'],20)
command('system-after',adb+['logcat','-b','system','-d','-t','500'],20)
result=dict(status=status,health_service_stability=ready,build_source_sha=manifest['build_source_sha'],identity='identity.json',command_receipts='commands.json',physical_hardware=False,talkback_manual='NOT_RUN',notes='Single bounded no-streaming installation attempt, one instrumentation invocation only if both APK installations succeed. No source edits.')
(run/'result.json').write_text(json.dumps(result,indent=2)+'\n')
(root/'latest-attempt.txt').write_text(str(run)+'\n')
print(json.dumps(dict(directory=str(run),**result)),flush=True)
