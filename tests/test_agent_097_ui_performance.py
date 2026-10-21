import argparse,copy,hashlib,importlib.util,json,struct
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]
def module(name):
 spec=importlib.util.spec_from_file_location(name,ROOT/'tools/device/ui_performance'/f'{name}.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
report=module('report');fixtures=module('fixtures');protocol=json.loads((ROOT/'tools/device/ui_performance/protocol.json').read_text())
def evidence():
 context={key:1 for key in protocol['requiredContext']};context.update(deviceFingerprint='physical/device/fingerprint',sourceSha='d'*40,appSha256='a'*64,benchmarkApkSha256='e'*64,installedAppSha256='a'*64,installedBenchmarkApkSha256='e'*64,executionStatus='COMPLETE',modelManifestSha256='b'*64,protocolSha256='c'*64,physical=True,abi='arm64-v8a',thermalBefore=0,thermalAfter=1,batteryPercent=80,inputResponseP95Ms=100,smallPssMaxMb=100,largePssMaxMb=200,unprofiledAppSha256='build')
 doc={'benchmarks':[{'name':name,'metrics':{'timeToInitialDisplayMs':{'runs':[100]*10},'frameDurationCpuMs':{'runs':[10]*10}}} for name in protocol['scenarios']]};return doc,context

def test_fixture_determinism_bounds_and_identity(tmp_path):
 first=fixtures.generate(tmp_path/'a');second=fixtures.generate(tmp_path/'b');assert first==second
 for name in ('small','large'):
  data=(tmp_path/'a'/f'a097-{name}.glb').read_bytes();assert struct.unpack_from('<III',data)==(0x46546c67,2,len(data))
  assert first['fixtures'][name]['synthetic']
  assert first['fixtures'][name]['triangles']<=32768

def test_complete_evidence_passes():
 d,c=evidence();assert report.evaluate(d,c,protocol)['status']=='PASS'
@pytest.mark.parametrize('change',[{'physical':False},{'thermalAfter':2},{'batteryPercent':29},{'abi':'x86_64'}])
def test_invalid_device_conditions(change):
 d,c=evidence();c.update(change)
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)
def test_missing_and_zero_metric_runs_fail():
 d,c=evidence();d['benchmarks'][0]['metrics']={}
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)
 d,c=evidence();d['benchmarks'][0]['metrics']['timeToInitialDisplayMs']['runs']=[]
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)
def test_tail_and_missing_counters_are_not_pass():
 d,c=evidence();d['benchmarks'][0]['metrics']['timeToInitialDisplayMs']['runs']=[4000]*10
 assert report.evaluate(d,c,protocol)['status']=='FAIL'
 d,c=evidence();del c['inputResponseP95Ms'];assert report.evaluate(d,c,protocol)['status']=='NOT_RUN'
def test_profile_requires_benefit_and_identical_conditions():
 d,c=evidence();before=report.evaluate(d,c,protocol);after=copy.deepcopy(before)
 assert report.compare(before,after)['retainBaselineProfile'] is False
 after['results']['coldLaunch']['timeToInitialDisplayMs']['p95']=90
 assert report.compare(before,after)['retainBaselineProfile'] is True
 after['results']['coldLaunch']['timeToInitialDisplayMs']['p99']=105
 with pytest.raises(ValueError):report.compare(before,after)

def test_profile_comparison_rejects_changed_benchmark_driver():
 d,c=evidence();before=report.evaluate(d,c,protocol);after=copy.deepcopy(before)
 after['context']['benchmarkApkSha256']='f'*64
 with pytest.raises(ValueError,match='Noncomparable'):report.compare(before,after)

def test_identity_and_context_values_are_required():
 d,c=evidence();c['appSha256']='filename.apk'
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)

@pytest.mark.parametrize('change',[{'installedAppSha256':'f'*64},{'installedBenchmarkApkSha256':'f'*64},{'sourceSha':'branch-name'},{'executionStatus':'FAIL'}])
def test_stale_or_unverified_installed_candidates_cannot_qualify(change):
 d,c=evidence();c.update(change)
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)

runner=module('run')
@pytest.mark.parametrize('fault',[None,'gradle_replaces_target','stale_installed_driver'])
def test_runner_binds_installed_target_and_driver_after_gradle(tmp_path,monkeypatch,fault):
 target=tmp_path/'target.apk';driver=tmp_path/'driver.apk'
 target.write_bytes(b'labelled synthetic target APK fixture');driver.write_bytes(b'labelled synthetic driver APK fixture')
 args=argparse.Namespace(serial='physical-fixture',apk=target,benchmark_apk=driver,ambient_c=22,refresh_rate_hz=60,unprofiled_apk=None,output=tmp_path/'run',gradle='synthetic-gradle')
 expected=dict(zip(runner.PACKAGES,(runner.sha(target),runner.sha(driver))))
 installed={};gradle_calls=[]
 monkeypatch.setattr(runner,'source_snapshot',lambda clean=True:'d'*40)
 def check_output(command,**kwargs):
  assert command[:3]==['adb','-s','physical-fixture'];parts=command[3:]
  if parts==['get-state']:return 'device\n'
  if parts[:2]==['shell','getprop']:
   return {'ro.kernel.qemu':'0','ro.boot.qemu':'0','ro.product.cpu.abi':'arm64-v8a','ro.build.fingerprint':'synthetic/physical/fixture'}[parts[2]]+'\n'
  if parts==['shell','dumpsys','battery']:return 'level: 80\n'
  if parts==['shell','dumpsys','thermalservice']:return 'Thermal Status: 0\n'
  if parts[:2]==['install','-r']:
   path=Path(parts[2]);package=runner.PACKAGES[0 if path==target else 1];installed[package]=runner.sha(path);return 'Success\n'
  if parts[:3]==['shell','pm','path']:return 'package:/data/app/'+parts[3]+'/base.apk\n'
  if parts[:2]==['shell','sha256sum']:
   package=parts[2].split('/')[3];return (('f'*64) if fault=='stale_installed_driver' and package==runner.PACKAGES[1] else installed[package])+'  '+parts[2]+'\n'
  if parts==['shell','dumpsys','meminfo',runner.PACKAGES[0]]:return 'Synthetic test-only meminfo\n'
  raise AssertionError(parts)
 def gradle(command,**kwargs):
  gradle_calls.append(command)
  if fault=='gradle_replaces_target':target.write_bytes(b'changed synthetic candidate')
 monkeypatch.setattr(runner.subprocess,'check_output',check_output);monkeypatch.setattr(runner.subprocess,'run',gradle)
 if fault:
  with pytest.raises(ValueError,match='frozen candidate|frozen candidate APK'):runner.run(args)
  assert json.loads((args.output/'context.json').read_text())['executionStatus']=='FAIL'
 else:
  context=runner.run(args)
  assert context['executionStatus']=='COMPLETE' and len(gradle_calls)==4
  assert len(context['installedIdentityObservations'])==5
  assert all(observation['apkSha256']==expected for observation in context['installedIdentityObservations'])
 d,c=evidence();c['refreshRateHz']=float('nan')
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)

validator=module('validate_target')
def manifest_pair():
 target='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.realityforge.scanner"><application android:debuggable="false"><profileable android:shell="true"/><activity android:name="com.realityforge.scanner.bench97.BenchmarkViewerActivity"/></application></manifest>'
 driver='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.realityforge.scanner.macrobenchmark"><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="com.realityforge.scanner.macrobenchmark"/></manifest>'
 return target,driver

def test_release_like_profileable_self_instrumenting_target():
 target,driver=manifest_pair();assert validator.manifests(target,driver)['targetNonDebuggable']
@pytest.mark.parametrize('bad',[('debuggable="false"','debuggable="true"'),('shell="true"','shell="false"'),('bench97.BenchmarkViewerActivity','WrongActivity')])
def test_invalid_benchmark_target_is_rejected(bad):
 target,driver=manifest_pair()
 with pytest.raises(ValueError):validator.manifests(target.replace(*bad),driver)
def test_wrong_test_target_is_rejected():
 target,driver=manifest_pair()
 with pytest.raises(ValueError):validator.manifests(target,driver.replace('android:targetPackage="com.realityforge.scanner.macrobenchmark"','android:targetPackage="com.realityforge.scanner"'))
