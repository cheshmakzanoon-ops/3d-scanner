"""External orchestration only: exact built APKs, real terminal parser, no test substitutions."""
from pathlib import Path
import argparse,datetime,fcntl,hashlib,importlib.util,json,re,shlex,subprocess
p=argparse.ArgumentParser();p.add_argument('--build',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--class',dest='classes',action='append',required=True);p.add_argument('--install',action='store_true');p.add_argument('--timeout',type=int,default=360);a=p.parse_args()
base=Path('/workspace/campaign-061-080');source=a.build/'source';br=json.loads((a.build/'result.json').read_text());out=a.output;out.mkdir(exist_ok=False)
record={'source_sha':br['source_commit'],'synthetic':True,'physical':False,'build_receipt':str(a.build/'result.json'),'build_receipt_sha256':hashlib.sha256((a.build/'result.json').read_bytes()).hexdigest(),'commands':[],'classes':[],'status':'WORKING'}
apks=[('com.realityforge.scanner',source/'app/build/outputs/apk/debug/app-debug.apk'),('com.realityforge.scanner.test',source/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]
record['artifacts']={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for _,path in apks}
adb=[str(base/'adb'),'-s','emulator-5556']
def save(): (out/'receipt.json').write_text(json.dumps(record,indent=2)+'\n')
def run(args,label,limit=40):
 command=adb+args;start=datetime.datetime.now(datetime.timezone.utc).isoformat()
 try:
  r=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=limit);data=r.stdout;code=r.returncode
 except subprocess.TimeoutExpired as e:data=e.stdout or b'';code='TIMEOUT'
 (out/(label+'.log')).write_bytes(data);record['commands'].append({'command':command,'started':start,'exit_code':code,'log':label+'.log'});save();return code,data.decode(errors='replace')
spec=importlib.util.spec_from_file_location('runner080',source/'tools/android-ui-e2e/run.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
save()
with (base/'device.lock').open('a') as lock:
 fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
 try:
  for package,_ in apks:run(['shell','am','force-stop',package],'stop-'+package)
  for index,(package,path) in enumerate(apks):
   if a.install:
    code,text=run(['install','--no-streaming','-r','-t',str(path)],'install-'+str(index),600)
    if code!=0 or not re.search(r'^Success\s*$',text,re.M):raise RuntimeError('Actual installation did not return Success')
   code,text=run(['shell','pm','path',package],'installed-path-'+str(index))
   paths=[line.removeprefix('package:').strip() for line in text.splitlines() if line.startswith('package:')]
   if code!=0 or len(paths)!=1 or not paths[0].startswith('/data/app/'):raise RuntimeError('Installed package path unavailable or splitAPK cannot be bound')
   code,text=run(['shell',shlex.join(['sha256sum',paths[0]])],'installed-sha-'+str(index))
   if code!=0 or text.split()[0]!=record['artifacts'][str(path)]:raise RuntimeError('Installed APK differs from exact build artifact')
  run(['shell','input','keyevent','224'],'wake');run(['shell','wm','dismiss-keyguard'],'dismiss-unsecured-keyguard')
  for key,label in [('ro.build.fingerprint','fingerprint'),('ro.build.version.sdk','api'),('ro.product.cpu.abi','abi')]:run(['shell','getprop',key],label)
  for index,cls in enumerate(a.classes):
   code,text=run(['shell','am','instrument','-w','-r','-e','class',cls,'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner'],'class-'+str(index),a.timeout)
   parsed=runner.parse_instrumentation(text,code if isinstance(code,int) else 124);record['classes'].append({'class':cls,'result':parsed});save();print(json.dumps(record['classes'][-1]),flush=True)
   if code=='TIMEOUT':
    for package,_ in apks:run(['shell','am','force-stop',package],'timeout-stop-'+str(index)+'-'+package)
  record['status']='PASS' if len(record['classes'])==len(a.classes) and all(r['result']['status']=='PASS' for r in record['classes']) else 'FAIL'
 except Exception as e:record['status']='FAIL';record['error']=str(e)
 finally:save()
print(json.dumps({'receipt':str(out/'receipt.json'),'status':record['status']}),flush=True)
