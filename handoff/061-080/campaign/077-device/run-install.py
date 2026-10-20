import datetime, fcntl, hashlib, json, pathlib, subprocess, time
root=pathlib.Path('/workspace/campaign-061-080/077-device')
run=pathlib.Path((root/'latest-attempt.txt').read_text().strip())
lock=open('/workspace/campaign-061-080/device.lock','a'); fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
records=json.loads((run/'commands.json').read_text())
adb=['/workspace/campaign-061-080/adb','-s','emulator-5556']
def command(label,args,timeout=20):
    start=datetime.datetime.now(datetime.timezone.utc).isoformat(); t=time.monotonic()
    try:
        p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
        code=p.returncode; out=p.stdout.decode(errors='replace'); expired=False
    except subprocess.TimeoutExpired as e: code=None; out=(e.stdout or b'').decode(errors='replace'); expired=True
    (run/(label+'.log')).write_text(out)
    item=dict(label=label,command=args,start_utc=start,duration_seconds=round(time.monotonic()-t,3),exit_code=code,timed_out=expired,log=label+'.log')
    records.append(item); (run/'commands.json').write_text(json.dumps(records,indent=2)+'\n'); print(json.dumps(item),flush=True)
    return code,out
identity=json.loads((run/'identity.json').read_text())
for a in identity['apks']: assert hashlib.sha256(pathlib.Path(a['path']).read_bytes()).hexdigest()==a['sha256'], 'APK pair changed'
result=json.loads((run/'result.json').read_text())
result['readiness_assessment']='Initial strict loop interrupted by shell command timeouts. system_server PID4585 was observed unchanged at09:07:35,09:09:18,09:09:39; finalpackage/activity checks succeeded and boot_completed1. Onebounded installation attempt follows, with a current PID check.'
code,pid=command('install-readiness-pid',adb+['shell','pidof','system_server'],25)
result['health_service_stability']=code==0 and pid.strip()=='4585'
status='NOT_RUN_GUEST_UNSTABLE'
if result['health_service_stability']:
    status='NOT_RUN_INSTALL_FAILED'; installed=True
    for i,a in enumerate(identity['apks']):
        code,out=command('install-'+str(i),adb+['install','--no-streaming','-r','-t',a['path']],180 if i==0 else 90)
        if code!=0 or 'Success' not in out: installed=False; break
    if installed:
        command('instrumentation-list',adb+['shell','pm','list','instrumentation'],25)
        code,out=command('actual-accessibility',adb+['shell','am','instrument','-w','-r','-e','class','com.realityforge.scanner.agents.a077.ActualAccessibilityTest','com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner'],300)
        if code==0 and 'OK (3 tests)' in out: status='PASS_3_TESTS'
        elif 'FAILURES!!!' in out or 'INSTRUMENTATION_STATUS_CODE: -2' in out: status='FAIL_ACTUAL_TESTS'
        else: status='NOT_COMPLETED_INSTRUMENTATION'
command('install-final-boot',adb+['shell','getprop','sys.boot_completed'],20)
command('install-final-pid',adb+['shell','pidof','system_server'],20)
command('install-crash-after',adb+['logcat','-b','crash','-d','-t','250'],25)
command('install-system-after',adb+['logcat','-b','system','-d','-t','500'],25)
result.update(status=status,notes='One bounded no-streaming installation attempt. One instrumentation invocation only if both APK installations succeed. No source edits. Physical hardware and human TalkBack review NOT_RUN.')
(run/'result.json').write_text(json.dumps(result,indent=2)+'\n'); print(json.dumps(dict(directory=str(run),**result)),flush=True)
