import copy,importlib.util,json,struct
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]
def module(name):
 spec=importlib.util.spec_from_file_location(name,ROOT/'tools/device/ui_performance'/f'{name}.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
report=module('report');fixtures=module('fixtures');protocol=json.loads((ROOT/'tools/device/ui_performance/protocol.json').read_text())
def evidence():
 context={key:1 for key in protocol['requiredContext']};context.update(deviceFingerprint='physical/device/fingerprint',appSha256='a'*64,modelManifestSha256='b'*64,protocolSha256='c'*64,physical=True,abi='arm64-v8a',thermalBefore=0,thermalAfter=1,batteryPercent=80,inputResponseP95Ms=100,smallPssMaxMb=100,largePssMaxMb=200,unprofiledAppSha256='build')
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

def test_identity_and_context_values_are_required():
 d,c=evidence();c['appSha256']='filename.apk'
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)
 d,c=evidence();c['refreshRateHz']=float('nan')
 with pytest.raises(ValueError):report.evaluate(d,c,protocol)
