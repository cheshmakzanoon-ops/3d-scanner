"""Run implemented checks on exactly bound artifacts; preserve all failed/unavailable gates."""
from pathlib import Path
import argparse,datetime,fcntl,hashlib,json,subprocess,sys
p=argparse.ArgumentParser();p.add_argument('--build',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
base=Path('/workspace/campaign-061-080');rpath=a.build/'result.json';br=json.loads(rpath.read_text());sha=br['source_commit'];source=a.build/'source';out=a.output;out.mkdir(exist_ok=False)
app=source/'app/build/outputs/apk/debug/app-debug.apk';test=source/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
identity={'source_sha':sha,'apk_sha256':hashlib.sha256(app.read_bytes()).hexdigest(),'test_apk_sha256':hashlib.sha256(test.read_bytes()).hexdigest(),'validation_receipt':str(rpath),'validation_receipt_sha256':hashlib.sha256(rpath.read_bytes()).hexdigest(),'adb_wrapper_sha256':hashlib.sha256((base/'adb-ui-nonstreaming').read_bytes()).hexdigest(),'transport_note':'Standard --no-streaming ADB installation via a recorded wrapper; no test argument or APK byte changes.'}
(out/'build-binding.json').write_text(json.dumps(identity,indent=2)+'\n')
classes=['com.realityforge.scanner.agents.a077.ActualAccessibilityTest','com.realityforge.scanner.agents.a072.ScreenInteractionTest','com.realityforge.scanner.agents.a074.ScreenInteractionTest','com.realityforge.scanner.agents.a075.ScreenInteractionTest','com.realityforge.scanner.agents.a079.ScreenInteractionTest','com.realityforge.scanner.agents.a076.DesignSystemDeviceTest','com.realityforge.scanner.PhotographicViewerTest#actualDialogViewerRendersAfterActivityPauseAndResume','com.realityforge.scanner.PhotographicViewerTest#externalDataTransformsAndExtensionsAreRejected','com.realityforge.scanner.PhotographicViewerTest#nonfiniteCoordinatesBadIndicesAndCorruptPngAreRejected']
fixture_out=base/('device-final-fixtures-'+sha[:12])
command=[sys.executable,str(base/'device-classes.py'),'--build',str(a.build),'--output',str(fixture_out),'--install','--timeout','420']
for cls in classes:command+=['--class',cls]
with (out/'fixture-driver.log').open('w') as f:code=subprocess.run(command,stdout=f,stderr=subprocess.STDOUT).returncode
fixture_receipt=fixture_out/'receipt.json'
fixture_status=json.loads(fixture_receipt.read_text()).get('status','FAIL') if fixture_receipt.exists() else 'FAIL'
print(json.dumps({'phase':'fixture_classes','driver_exit':code,'status':fixture_status,'receipt':str(fixture_receipt)}),flush=True)
# Establish baseline test fixture only between instrumentation processes.
prep=[]
with (base/'device.lock').open('a') as lock:
 fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
 for args in [['shell','am','force-stop','com.realityforge.scanner'],['shell','am','force-stop','com.realityforge.scanner.test'],['shell','input','keyevent','224'],['shell','wm','dismiss-keyguard'],['shell','settings','get','system','font_scale'],['shell','settings','put','system','font_scale','1.0']]:
  cmd=[str(base/'adb'),'-s','emulator-5556']+args
  try:v=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=40);row={'command':cmd,'exit_code':v.returncode,'output':v.stdout.decode(errors='replace')}
  except subprocess.TimeoutExpired as e:row={'command':cmd,'exit_code':'TIMEOUT','output':(e.stdout or b'').decode(errors='replace')}
  prep.append(row);(out/'ui-fixture-preparation.json').write_text(json.dumps(prep,indent=2)+'\n')
 cmd=[sys.executable,str(source/'tools/android-ui-e2e/run.py'),'--repo',str(base/'combined'),'--source-sha',sha,'--apk',str(app),'--test-apk',str(test),'--build-receipt',str(out/'build-binding.json'),'--output',str(out/'ui'),'--adb',str(base/'adb-ui-nonstreaming'),'--serial','emulator-5556','--all-required','--timeout','600']
 (out/'ui-command.json').write_text(json.dumps(cmd,indent=2)+'\n')
 with (out/'ui-driver.log').open('w') as f:ui_code=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT).returncode
print(json.dumps({'phase':'actual_ui_all_required','runner_exit':ui_code,'receipt':str(out/'ui/evidence.json')}),flush=True)
summary={'source_sha':sha,'build_binding':identity,'fixture_receipt':str(fixture_receipt),'fixture_status':fixture_status,'ui_receipt':str(out/'ui/evidence.json'),'ui_exit_code':ui_code,'finished_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(out/'receipt.json').write_text(json.dumps(summary,indent=2)+'\n')
