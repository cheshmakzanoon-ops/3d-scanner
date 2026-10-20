from pathlib import Path
import subprocess,fcntl,json,hashlib,datetime,importlib.util,sys
base=Path('/workspace/campaign-061-080');build=base/'final-validation/3b9f9157753b';source=build/'source';out=base/'device-3b9f915';out.mkdir(exist_ok=False)
adb=[str(base/'adb'),'-s','emulator-5556'];records=[]
record={'source_sha':'3b9f9157753b934350e05287a6ff33bbe46c665a','synthetic':True,'physical':False,'build_receipt':str(build/'result.json'),'commands':records,'status':'WORKING','classes':[]}
artifacts=[source/'app/build/outputs/apk/debug/app-debug.apk',source/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
record['artifacts']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts}
def save(): (out/'receipt.json').write_text(json.dumps(record,indent=2)+'\n')
def run(args,label,limit):
 command=adb+args;start=datetime.datetime.now(datetime.timezone.utc).isoformat()
 try:
  result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=limit);data=result.stdout;code=result.returncode
 except subprocess.TimeoutExpired as error:data=error.stdout or b'';code='TIMEOUT'
 (out/(label+'.log')).write_bytes(data);records.append({'command':command,'started':start,'exit_code':code,'log':label+'.log'});save();return code,data.decode(errors='replace')
spec=importlib.util.spec_from_file_location('runner080',source/'tools/android-ui-e2e/run.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
classes=['com.realityforge.scanner.agents.a068.GlbParserCorpusTest','com.realityforge.scanner.agents.a077.ActualAccessibilityTest','com.realityforge.scanner.agents.a072.ScreenInteractionTest','com.realityforge.scanner.agents.a074.ScreenInteractionTest','com.realityforge.scanner.agents.a075.ScreenInteractionTest','com.realityforge.scanner.agents.a079.ScreenInteractionTest','com.realityforge.scanner.agents.a076.DesignSystemDeviceTest','com.realityforge.scanner.agents.a078.LocalizedResourcesDeviceTest','com.realityforge.scanner.PhotographicViewerTest','com.realityforge.scanner.agents.a070.ViewerControlsTest','com.realityforge.scanner.agents.a070.RendererFallbackTest']
save()
with (base/'device.lock').open('a') as lock:
 fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
 try:
  for index,apk in enumerate(artifacts):
   code,text=run(['install','--no-streaming','-r','-t',str(apk)],'install-'+str(index),600)
   if code!=0 or '\nSuccess' not in '\n'+text:raise RuntimeError('Installation did not return Success; actual class execution NOT_RUN')
  run(['shell','input','keyevent','224'],'wake',30)
  run(['shell','wm','dismiss-keyguard'],'dismiss-unsecured-keyguard',30)
  run(['shell','getprop','ro.build.fingerprint'],'fingerprint',30)
  run(['shell','getprop','ro.build.version.sdk'],'api',30)
  run(['shell','getprop','ro.product.cpu.abi'],'abi',30)
  for index,cls in enumerate(classes):
   code,text=run(['shell','am','instrument','-w','-r','-e','class',cls,'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner'],'class-'+str(index),360)
   parsed=runner.parse_instrumentation(text,code if isinstance(code,int) else 124)
   record['classes'].append({'class':cls,'result':parsed});save();print(json.dumps(record['classes'][-1]),flush=True)
   if code=='TIMEOUT':break
  record['status']='PASS' if len(record['classes'])==len(classes) and all(r['result']['status']=='PASS' for r in record['classes']) else 'FAIL'
 except Exception as error: record['status']='FAIL';record['error']=str(error)
 finally:save()
print(json.dumps({'receipt':str(out/'receipt.json'),'status':record['status']}),flush=True)
