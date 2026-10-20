import datetime,json,os,pathlib,signal,subprocess,time
base=pathlib.Path('/workspace/campaign/evidence/092/emulator-probe')
serial='emulator-5580'
env=os.environ.copy()
env.update({'ANDROID_HOME':'/workspace/campaign/emulator-sdk','ANDROID_SDK_ROOT':'/workspace/campaign/emulator-sdk','ANDROID_USER_HOME':'/workspace/campaign/emulator-user','ANDROID_EMULATOR_HOME':'/workspace/campaign/emulator-user','ANDROID_AVD_HOME':'/workspace/campaign/emulator-user/avd','XDG_CACHE_HOME':'/workspace/campaign/emulator-user/cache'})
args=['/workspace/campaign/emulator-sdk/emulator/emulator','-avd','rf092_api29_software','-port','5580','-accel','off','-gpu','swiftshader','-cores','1','-memory','2048','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics','-camera-back','none','-camera-front','none','-verbose']
started=time.monotonic()
receipt={'schema':1,'status':'BOOTING','serial':serial,'argv':args,'environment_overrides':{key:env[key] for key in ('ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_USER_HOME','ANDROID_EMULATOR_HOME','ANDROID_AVD_HOME','XDG_CACHE_HOME')},'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'boot_timeout_seconds':360,'physical_qualification':'NOT_RUN','specialist_qualification':'NOT_RUN'}
def run(*command,timeout=5):
    try:
        value=subprocess.run(['/workspace/android-sdk/platform-tools/adb','-s',serial,*command],text=True,capture_output=True,timeout=timeout)
        return {'returncode':value.returncode,'stdout':value.stdout.strip(),'stderr':value.stderr.strip()}
    except subprocess.TimeoutExpired:
        return {'returncode':124,'stdout':'','stderr':'bounded adb timeout'}
def save():
    (base/'boot-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
with (base/'emulator.log').open('w') as log:
    process=subprocess.Popen(args,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    receipt['emulator_pid']=process.pid;save()
    try:
        while time.monotonic()-started<360:
            result=process.poll()
            if result is not None:
                receipt.update(status='FAIL',reason='Official emulator exited before boot completion',returncode=result);break
            boot=run('shell','getprop','sys.boot_completed')
            receipt['last_boot_probe']=boot
            receipt['elapsed_seconds']=round(time.monotonic()-started,2)
            if boot['returncode']==0 and boot['stdout']=='1':
                receipt['identity']={key:run('shell','getprop',key)['stdout'] for key in ['ro.build.fingerprint','ro.build.version.sdk','ro.product.cpu.abi','ro.kernel.qemu','init.svc.bootanim']}
                receipt['page_size']=run('shell','getconf','PAGE_SIZE')
                receipt['package_manager']=run('shell','pm','path','android',timeout=20)
                if receipt['package_manager']['returncode']==0 and receipt['identity']['ro.kernel.qemu']=='1':
                    receipt.update(status='BOOTED',boot_seconds=round(time.monotonic()-started,2));run('shell','input','keyevent','82');save()
                    print(json.dumps({'status':'BOOTED','serial':serial,'seconds':receipt['boot_seconds'],'identity':receipt['identity']}),flush=True)
                    # Keep only this disposable emulator available for parent-supplied candidate tests.
                    while time.monotonic()-started<1800 and not (base/'stop-requested').exists() and process.poll() is None:
                        time.sleep(2)
                    break
            save();time.sleep(3)
        else:
            receipt.update(status='BLOCKED_EXTERNAL',reason='Software CPU emulator did not complete boot within360s; actual runtime tests NOT_RUN')
        receipt['final_elapsed_seconds']=round(time.monotonic()-started,2)
        if receipt['status']!='BOOTED':
            receipt['last_transport']=run('get-state')
            receipt['logcat_tail']=run('logcat','-d','-t','100',timeout=15)
            save();print(json.dumps({'status':receipt['status'],'reason':receipt.get('reason'),'elapsed_seconds':receipt['final_elapsed_seconds']}),flush=True)
    finally:
        if process.poll() is None:
            os.killpg(process.pid,signal.SIGTERM)
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=10)
        receipt['emulator_exitcode']=process.returncode;receipt['stopped_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();save()
