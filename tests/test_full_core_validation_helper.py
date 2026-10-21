"""Pure mocked guards; never starts Gradle, a JVM, or reconstruction."""
import importlib.util,json,subprocess,signal
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]/'tools/full_core_validation'
@pytest.fixture(params=['core','publication'])
def helper(request):return ROOT/request.param/'validate.py'
@pytest.fixture
def module(helper):
 spec=importlib.util.spec_from_file_location('full_core_helper',helper);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
@pytest.fixture
def config(tmp_path,module):
 m=module;c={'schema':1,'repo':str(tmp_path/'repo'),'native':{},'provenance':[]}
 def artifact(name):
  p=tmp_path/name;p.write_text(name);return {'path':str(p),'sha256':m.sha(p)}
 for key in ['environmentScript','java','gradle','opencvAar','opencvClasses','dropOpenCvRuntime','appClasses']:c[key]=artifact(key)
 for key in ['opencv','shim','engine']:c['native'][key]=artifact(key)
 c['provenance']=[artifact('historical-receipt')]
 p=tmp_path/'config.json';p.write_text(json.dumps(c));return p,c

def test_explicit_config_is_hash_bound(module,config):
 p,c=config;assert module.read_config(p,module.sha(p))==c
@pytest.mark.parametrize('key',['environmentScript','java','gradle','opencvAar','opencvClasses','dropOpenCvRuntime','appClasses','native','provenance'])
def test_mutated_artifact_rejected(module,config,key):
 p,c=config;entry=c[key] if key not in ['native','provenance'] else c[key]['shim'] if key=='native' else c[key][0]
 Path(entry['path']).write_text('changed')
 with pytest.raises(ValueError):module.read_config(p,module.sha(p))
@pytest.mark.parametrize('kind',['hash','schema','missing','native_set','no_provenance'])
def test_config_contract_rejects(module,config,kind):
 p,c=config;expected=module.sha(p)
 if kind=='schema':c['schema']=2
 elif kind=='missing':c.pop('gradle')
 elif kind=='native_set':c['native'].pop('engine')
 elif kind=='no_provenance':c['provenance']=[]
 else:c['repo']='different'
 p.write_text(json.dumps(c))
 if kind!='hash':expected=module.sha(p)
 with pytest.raises(ValueError):module.read_config(p,expected)
@pytest.mark.parametrize('suites,expected',[([],False),([{'tests':'1','failures':'0','errors':'0','skipped':'0'}],True),([{'tests':'0'}],False),([{'tests':'2'}],False),([{'tests':'1','skipped':'1'}],False),([{'tests':'1','errors':'1'}],False),([{'tests':'1','failures':'1'}],False)])
def test_exact_one_actual_success_required(module,suites,expected):assert module.junit_pass(suites)==expected
@pytest.mark.parametrize('answers,expected,signals',[([0],'EXIT_0',[]),([1],'EXIT_1',[]),(['timeout',0],'OUTER_TIMEOUT_600S',[signal.SIGTERM]),(['timeout','timeout',-9],'OUTER_TIMEOUT_600S',[signal.SIGTERM,signal.SIGKILL])])
def test_process_group_watchdog(module,monkeypatch,answers,expected,signals):
 seen=[];waits=[];remaining=list(answers)
 class Proc:
  pid=12345
  def wait(self,timeout):
   waits.append(timeout);value=remaining.pop(0)
   if value=='timeout':raise subprocess.TimeoutExpired('fake',timeout)
   return value
 monkeypatch.setattr(module.os,'killpg',lambda pid,sig:seen.append((pid,sig)))
 assert module.wait_bounded(Proc())==expected
 assert seen==[(12345,sig) for sig in signals] and waits[0]==600

def test_receipt_atomic_replace(module,tmp_path):
 p=tmp_path/'receipt.json';module.write_receipt(p,{'outcome':'PENDING_RESTORATION'});module.write_receipt(p,{'outcome':'PASS'})
 assert json.loads(p.read_text())=={'outcome':'PASS'} and not p.with_name('receipt.json.partial').exists()

def test_source_contract_keeps_real_gate_and_restoration(helper):
 s=helper.read_text();init=helper.with_name('probe.init.gradle').read_text()
 assert "'outcome':'PENDING_RESTORATION' if all_checks else 'FAILED'" in s
 assert "receipt['outcome']='PASS' if all_checks else 'FAILED'" in s
 assert "if not restoration.get('restored') or errors:all_checks=False" in s
 for text in ['before==after','compiled_unchanged','post_commit==args.expected_commit',"post_status==''",'junit_ok',":app:compileDebugUnitTestKotlin","remainingProbeClasses"]:assert text in s
 for text in ['forkEvery=1','maxParallelForks=1','-XX:ActiveProcessorCount=2','owners[n].size()==1','owners[n][0].sha256==h','compiled-probes.json','actual-classpath.json']:assert text in init


def test_unreviewed_native_hash_rejected(module):
 c={k:{'sha256':v} for k,v in module.NATIVE_PINS.items()}
 module.validate_native_pins(c)
 c['shim']['sha256']='0'*64
 with pytest.raises(ValueError):module.validate_native_pins(c)

def test_modes_preserve_distinct_contracts_and_exact_sources():
 import hashlib
 for mode,name,h in [('core','SparsePipelineFullCoreHostTest.kt','157927f1a84acdbb9454d6fa67fe18643ff6a059a19a06f62b36a8335fff1b7c'),('publication','SparsePipelinePublicationHostTest.kt','7266368576dfb86e76afe83137af3c94f7098e0df9a345563192d00ebf8db5be')]:
  assert hashlib.sha256((ROOT/mode/name).read_bytes()).hexdigest()==h
  init=(ROOT/mode/'probe.init.gradle').read_text()
  for text in ['robolectric.offline','failOnNoMatchingTests=true',"maxHeapSize='1g'",'ofMinutes(5)']:assert text in init
  assert '180_000' in (ROOT/mode/name).read_text()
 assert 'com.realityforge.scanner.export.NativeAssetExports' in (ROOT/'publication/SparsePrefixRunner.kt').read_text()
