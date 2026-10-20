"""Run one explicitly frozen APK pair; no retries and no builds or source changes."""
from pathlib import Path
import argparse,datetime,hashlib,json,re,subprocess,time

SERIAL='emulator-5556'
RUNNER='com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner'
BATCHES=[
 ('platform',900,[
  'com.realityforge.scanner.agents.a031.PixelProjectionDeviceTest',
  'com.realityforge.scanner.agents.a033.FeatureCacheDeviceTest',
  'com.realityforge.scanner.engine.imaging.PyramidRectifierTest',
  'com.realityforge.scanner.agents.a012.ImageOwnershipHandoffInstrumentedTest',
  'com.realityforge.scanner.agents.a012.ImageDecodingInstrumentedTest']),
 ('bridge_geometry',1800,[
  'com.realityforge.scanner.agents.a003.BundleProblemBridgeTest',
  'com.realityforge.scanner.NativeSmokeTest#sparseOperationLeasesExposeVersionedProgressAndTypedCancellation',
  'com.realityforge.scanner.NativeSmokeTest#nativeCancellationTokensAreIsolatedAndRejectStaleHandles',
  'com.realityforge.scanner.NativeSmokeTest#refinedDepthUsesActualCoarsePhotographsWithNoFabricatedPoints',
  'com.realityforge.scanner.NativeSmokeTest#rectifiedCpuDenseStereoReconstructsActualPixelsThroughJni',
  'com.realityforge.scanner.DepthCheckpointDeviceTest',
  'com.realityforge.scanner.agents.a037.InitializerNativeTest',
  'com.realityforge.scanner.agents.a038.PnpPlanarNativeTest',
  'com.realityforge.scanner.agents.a040.SparseSupportNativeTest',
  'com.realityforge.scanner.IncrementalNativeRegistrationTest']),
 ('pipeline',2700,['com.realityforge.scanner.agents.a002.PipelineServicesDeviceTest']),
 ('resources',1800,[
  'com.realityforge.scanner.agents.a032.FeatureMemoryDeviceTest',
  'com.realityforge.scanner.agents.a035.MatchingEvidenceDeviceTest',
  'com.realityforge.scanner.engine.features.FeatureExtractorTest',
  'com.realityforge.scanner.engine.matching.FeatureMatcherTest',
 ])]

def output(value):print(json.dumps(value),flush=True)
def captured(command,timeout):
 try:
  result=subprocess.run(command,capture_output=True,text=True,timeout=timeout)
  return result.returncode,result.stdout+result.stderr
 except subprocess.TimeoutExpired as error:
  def string(value):return value.decode(errors='replace') if isinstance(value,bytes) else value or ''
  return 124,string(error.stdout)+string(error.stderr)
def result(text,exit_code,timed_out):
 ok=re.search(r'OK \((\d+) tests?\)',text)
 failed=re.search(r'Tests run:\s*(\d+),\s*Failures:\s*(\d+)',text)
 status=re.findall(r'INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)',text)
 passed=sum(s=='0' for s in status)
 skipped=sum(s in ['-3','-4'] for s in status)
 if timed_out:classification='TIMEOUT'
 elif 'INSTRUMENTATION_FAILED' in text or 'INSTRUMENTATION_RESULT: shortMsg=' in text:classification='RUNNER_OR_PROCESS_ERROR'
 elif failed or any(s in ['-1','-2'] for s in status):classification='ASSERTION_FAIL' if 'AssertionError' in text else 'TEST_EXCEPTION'
 elif exit_code!=0:classification='RUNNER_ERROR'
 elif ok and int(ok.group(1))>0 and passed==int(ok.group(1)) and not skipped:classification='PASS'
 elif ok:classification='ZERO_OR_SKIPPED_TESTS'
 else:classification='INCOMPLETE_RUNNER_RESULT'
 return {'classification':classification,'tests_run':int(ok.group(1)) if ok else int(failed.group(1)) if failed else None,
         'passed_statuses':passed,'failed_statuses':sum(s in ['-1','-2'] for s in status),'skipped_statuses':skipped}

def main():
 parser=argparse.ArgumentParser()
 parser.add_argument('--source',required=True);parser.add_argument('--app',required=True);parser.add_argument('--app-sha256',required=True)
 parser.add_argument('--qualification',choices=['FINAL_CANDIDATE','PRE_FIX_CHECKPOINT'],default='FINAL_CANDIDATE')
 parser.add_argument('--build-receipt')
 parser.add_argument('--test',required=True);parser.add_argument('--test-sha256',required=True)
 parser.add_argument('--output',required=True);parser.add_argument('--batch',action='append',choices=[b[0] for b in BATCHES])
 args=parser.parse_args()
 assert re.fullmatch('[0-9a-f]{40}',args.source)
 root=Path(args.output);root.mkdir(parents=True,exist_ok=False)
 artifacts=[]
 for value,digest in [(args.app,args.app_sha256),(args.test,args.test_sha256)]:
  path=Path(value).resolve(strict=True)
  assert str(path).startswith('/workspace/') and re.fullmatch('[0-9a-f]{64}',digest)
  assert hashlib.sha256(path.read_bytes()).hexdigest()==digest
  artifacts.append({'path':str(path),'sha256':digest})
 identity={'source_sha':args.source,'qualification':args.qualification,'device':SERIAL,'apk_artifacts':artifacts,'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'batches':[batch for batch in BATCHES if not args.batch or batch[0] in args.batch]}
 if args.build_receipt:
  receipt_path=Path(args.build_receipt).resolve(strict=True);receipt_bytes=receipt_path.read_bytes();receipt=json.loads(receipt_bytes)
  assert receipt['source_sha']==args.source
  for artifact,receipt_name in zip(artifacts,['app-debug.apk','app-debug-androidTest.apk']):
   assert receipt['artifacts'][receipt_name]['sha256']==artifact['sha256']
  identity['build_receipt']={'path':str(receipt_path),'sha256':hashlib.sha256(receipt_bytes).hexdigest(),'command':receipt.get('command'),'build_configuration':receipt.get('build_configuration'),'performance_classification':receipt.get('performance_classification')}
 (root/'artifacts.json').write_text(json.dumps(identity,indent=2)+'\n')
 for key,command in [('boot',['getprop','sys.boot_completed']),('activity',['service','check','activity']),('package',['service','check','package'])]:
  code,text=captured(['adb','-s',SERIAL,'shell',*command],30)
  (root/f'readiness-{key}.log').write_text(text)
  assert code==0 and (text.strip()=='1' if key=='boot' else ': found' in text),f'Framework not ready: {key}'
 code,text=captured(['adb','-s',SERIAL,'shell','getprop'],30)
 properties=dict(re.findall(r'^\[([^\]]+)\]: \[([^\]]*)\]$',text,re.MULTILINE))
 selected={key:properties.get(key) for key in ['ro.build.version.sdk','ro.build.version.release','ro.product.cpu.abi','ro.build.fingerprint','ro.kernel.qemu','ro.product.model']}
 (root/'device-environment.json').write_text(json.dumps({'exit':code,'properties':selected,'physical_device_claim':False},indent=2)+'\n')
 for index,artifact in enumerate(artifacts):
  start=time.monotonic();code,text=captured(['adb','-s',SERIAL,'install','-r','-t','--no-streaming',artifact['path']],600)
  (root/f'install-{index}.log').write_text(text)
  output({'phase':'install','qualification':args.qualification,'source':args.source,'artifact':artifact,'exit':code,'seconds':round(time.monotonic()-start,2),'output':text.strip()})
  if code!=0 or 'Success' not in text:raise SystemExit('SETUP_ERROR: install failed; no tests run')
 summaries=[]
 for name,timeout,classes in BATCHES:
  if args.batch and name not in args.batch:continue
  path=root/f'{name}.log';start=time.monotonic();timed_out=False
  output({'phase':'test_start','qualification':args.qualification,'batch':name,'source':args.source,'timeout_seconds':timeout,'classes':classes})
  with path.open('w') as log:
   process=subprocess.Popen(['adb','-s',SERIAL,'shell','am','instrument','-w','-r','-e','a032_source_sha',args.source,'-e','a035_source_sha',args.source,'-e','class',','.join(classes),RUNNER],stdout=log,stderr=subprocess.STDOUT,text=True)
   try:code=process.wait(timeout=timeout)
   except subprocess.TimeoutExpired:
    timed_out=True;process.kill();code=process.wait()
  text=path.read_text(errors='replace');summary=result(text,code,timed_out)
  summary.update({'phase':'test_result','qualification':args.qualification,'batch':name,'source_sha':args.source,'exit':code,'seconds':round(time.monotonic()-start,2),'log_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'log':str(path)})
  (root/f'{name}.json').write_text(json.dumps(summary,indent=2)+'\n');output(summary);summaries.append(summary)
  log_code,log_text=captured(['adb','-s',SERIAL,'logcat','-d','-t','2000'],30)
  (root/f'{name}-logcat.log').write_text(log_text)
  if name=='resources':
   listing_code,listing=captured(['adb','-s',SERIAL,'shell','run-as','com.realityforge.scanner','ls','cache'],30)
   (root/'device-evidence-list.log').write_text(listing)
   evidence=[]
   for filename in listing.splitlines() if listing_code==0 else []:
    if not re.fullmatch(r'a0(?:32-memory|35-matching)-evidence-[0-9]+\.json',filename):continue
    try:
     pulled=subprocess.run(['adb','-s',SERIAL,'exec-out','run-as','com.realityforge.scanner','cat',f'cache/{filename}'],capture_output=True,timeout=30)
     report=json.loads(pulled.stdout)
     if pulled.returncode!=0 or report.get('sourceSha')!=args.source:continue
     destination=root/'device-evidence'/filename;destination.parent.mkdir(exist_ok=True)
     destination.write_bytes(pulled.stdout)
     evidence.append({'path':str(destination),'sha256':hashlib.sha256(pulled.stdout).hexdigest(),'source_sha':report.get('sourceSha')})
    except (subprocess.TimeoutExpired,ValueError):continue
   (root/'device-evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
  if timed_out:
   stop_code,stop_text=captured(['adb','-s',SERIAL,'shell','am','force-stop','com.realityforge.scanner'],30)
   (root/'timeout-stop.log').write_text(f'exit={stop_code}\n{stop_text}')
   break
  if summary['classification'] in ['RUNNER_ERROR','RUNNER_OR_PROCESS_ERROR','INCOMPLETE_RUNNER_RESULT']:break
 (root/'summary.json').write_text(json.dumps(summaries,indent=2)+'\n')
 return 0 if summaries and all(s['classification']=='PASS' for s in summaries) else 1

if __name__=='__main__':
 try:raise SystemExit(main())
 except (AssertionError,OSError) as error:
  output({'phase':'setup_error','classification':'SETUP_ERROR','message':str(error)})
  raise SystemExit(2)
