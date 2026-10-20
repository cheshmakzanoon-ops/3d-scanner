from pathlib import Path
import datetime,fcntl,hashlib,json,os,re,subprocess,time
base=Path('/workspace/campaign/evidence/092/emulator-5582/reboot-diagnostic');assert not base.exists();base.mkdir()
serial='emulator-5582';adb='/workspace/android-sdk/platform-tools/adb';guardian=384684;qemu=384688
os.kill(guardian,0);os.kill(qemu,0)
lock=open('/workspace/campaign/device-5582.lock','a');fcntl.flock(lock,fcntl.LOCK_EX)
receipt={'schema':1,'status':'REBOOTING','serial':serial,'guardian_pid':guardian,'emulator_pid':qemu,'guardian_tool_session':21665,'boot_timeout_seconds':360,'preserve_userdata':True,'wipe':False,'app_installation':'NOT_RUN','instrumentation':'NOT_RUN','physical_qualification':'NOT_RUN','specialist_qualification':'NOT_RUN','device_owner_ack':'No5582 pendingADB; targets/providers stopped; deviceowner now exclusively uses5584','commands':[]}
def save():
 p=base/'reboot-receipt.json';temp=base/'reboot-receipt.tmp';temp.write_text(json.dumps(receipt,indent=2,sort_keys=True)+'\n');temp.replace(p)
def run(*args,timeout=5,deadline=None):
 if deadline is not None:
  remaining=deadline-time.monotonic()
  if remaining<=0:return {'returncode':124,'stdout':'','stderr':'360s boot budget exhausted'}
  timeout=min(timeout,remaining)
 command=[adb,'-s',serial,*args];started=time.monotonic()
 try:
  result=subprocess.run(command,text=True,capture_output=True,timeout=timeout)
  data={'returncode':result.returncode,'stdout':result.stdout.strip(),'stderr':result.stderr.strip()}
 except subprocess.TimeoutExpired as e:
  def text(v):return v.decode('utf-8','replace') if isinstance(v,bytes) else v or ''
  data={'returncode':124,'stdout':text(e.stdout),'stderr':text(e.stderr)+' bounded timeout'}
 receipt['commands'].append({'argv':command,'elapsed_seconds':round(time.monotonic()-started,3),**data});return data
try:
 receipt['previous_boot_id']=run('shell','cat','/proc/sys/kernel/random/boot_id')
 start=time.monotonic();deadline=start+360;receipt['started_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();save()
 receipt['reboot_command']=run('reboot',timeout=20,deadline=deadline);save()
 if receipt['reboot_command']['returncode']!=0:raise RuntimeError('Selected guest reboot command failed; no acceptance')
 while time.monotonic()<deadline:
  os.kill(guardian,0);os.kill(qemu,0)
  boot=run('shell','getprop','sys.boot_completed',deadline=deadline);receipt['last_boot_probe']=boot;receipt['elapsed_seconds']=round(time.monotonic()-start,2)
  if boot['returncode']==0 and boot['stdout']=='1':
   receipt['identity']={key:run('shell','getprop',key,deadline=deadline) for key in ['ro.build.fingerprint','ro.build.version.sdk','ro.product.cpu.abi','ro.kernel.qemu','init.svc.bootanim']}
   receipt['page_size']=run('shell','getconf','PAGE_SIZE',deadline=deadline)
   receipt['package_manager']=run('shell','pm','path','android',timeout=20,deadline=deadline)
   receipt['boot_id']=run('shell','cat','/proc/sys/kernel/random/boot_id',deadline=deadline)
   identity={key:value['stdout'] for key,value in receipt['identity'].items()};old=receipt['previous_boot_id'];new=receipt['boot_id']
   valid_identity=all(value['returncode']==0 for value in receipt['identity'].values()) and bool(identity['ro.build.fingerprint']) and identity['ro.build.version.sdk']=='29' and identity['ro.product.cpu.abi']=='x86_64' and identity['ro.kernel.qemu']=='1' and identity['init.svc.bootanim']=='stopped'
   valid_boot_id=old['returncode']==new['returncode']==0 and re.fullmatch('[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}',new['stdout']) and new['stdout']!=old['stdout']
   if (valid_identity and valid_boot_id and receipt['page_size']=={'returncode':0,'stdout':'4096','stderr':''} and receipt['package_manager']['returncode']==0 and receipt['package_manager']['stdout']=='package:/system/framework/framework-res.apk'):
    receipt.update(status='BOOT_READY',boot_seconds=round(time.monotonic()-start,2),guardian_preserved=True,emulator_process_preserved=True,boot_id_changed=True);break
  save();time.sleep(min(3,max(0,deadline-time.monotonic())))
 else:receipt.update(status='FAIL',reason='Preserved guest did not regain all required boot/identity/page/pm checks within360s')
except Exception as error:receipt.update(status='FAIL',reason=str(error))
finally:
 receipt['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();save();fcntl.flock(lock,fcntl.LOCK_UN);lock.close()
 p=base/'reboot-receipt.json';print(json.dumps({'status':receipt['status'],'receipt':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'boot_seconds':receipt.get('boot_seconds'),'reason':receipt.get('reason')}),flush=True)
raise SystemExit(0 if receipt['status']=='BOOT_READY' else 1)
