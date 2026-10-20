"""Verify retained immutable artifacts and update only 081/082 owned metadata."""
from pathlib import Path
import hashlib,json,re,subprocess,xml.etree.ElementTree as ET

FROZEN='86df647bcdba4b110ad36dfb6ce572adb4261462'
NATIVE=Path('/workspace/campaign/evidence/100/native-final/evidence.json')
FUZZ=Path('/workspace/campaign/evidence/100/fuzz-final/evidence.json')

def digest(path):
 with Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def checked(path,expected):
 actual=digest(path)
 assert actual==expected,(str(path),actual,expected)
 return actual
def junit(path):
 root=ET.parse(path).getroot();cases=list(root.iter('testcase'))
 result={'tests':len(cases),'failed':sum(c.find('failure') is not None for c in cases),'errors':sum(c.find('error') is not None for c in cases),'skipped':sum(c.find('skipped') is not None for c in cases)}
 result['passed']=result['tests']-result['failed']-result['errors']-result['skipped']
 suites=[s for s in root.iter('testsuite') if not s.findall('testsuite')]
 assert sum(int(s.get('tests',0)) for s in suites)==result['tests']
 result.update(path=str(path),sha256=digest(path))
 return result
native=json.loads(NATIVE.read_text());fuzz=json.loads(FUZZ.read_text())
assert native['source_sha']==fuzz['source_sha']==FROZEN
assert native['dirty_tree'] is False and fuzz['source_dirty'] is False
assert native['result']=='FAIL' and len(native['configurations'])==4
matrix=[]
for config in native['configurations']:
 assert len(config['commands'])==11
 for command in config['commands']:checked(command['log'],command['log_sha256'])
 for item in config['binaries']+config['artifacts']:checked(item['path'],item['sha256'])
 checked(config['compiler'],config['compiler_sha256'])
 for item in config.get('python_preloads',[]):checked(item['path'],item['sha256'])
 ctest=next(command for command in config['commands'] if command['command'][0]=='ctest')
 assert ctest['exit_code']==0 and '100% tests passed, 0 tests failed out of 11' in Path(ctest['log']).read_text()
 pytest=next(command for command in config['commands'] if '-m' in command['command'] and 'pytest' in command['command'])
 xml=Path(next(arg.split('=',1)[1] for arg in pytest['command'] if arg.startswith('--junitxml=')))
 counts=junit(xml)
 sanitized=config['name'].endswith('-sanitize')
 assert counts['tests']==764 and counts['passed']==(740 if sanitized else 764) and counts['failed']==(24 if sanitized else 0) and counts['errors']==counts['skipped']==0
 python_commands=config['commands'][3:]
 assert len(python_commands)==8
 failed=[command for command in python_commands if command['exit_code']!=0]
 assert len(failed)==(8 if sanitized else 0)
 if sanitized:
  assert config['sanitizer_options']['ASAN_OPTIONS']=='halt_on_error=1:detect_leaks=1'
  assert config['sanitizer_options']['UBSAN_OPTIONS']=='halt_on_error=1:print_stacktrace=1'
  assert all('LeakSanitizer' in Path(command['log']).read_text() for command in failed)
 matrix.append({'configuration':config['name'],'aggregate_result':config['result'],'native_ctest':{'passed':11,'failed':0,'log':ctest['log'],'sha256':ctest['log_sha256']},'pytest_cases':counts,'python_process_exit':pytest['exit_code'],'python_commands':8,'failed_python_commands':len(failed),'sanitizer_options':config['sanitizer_options'],'synthetic_numerical_scenes':len(config['numeric_metrics']),'benchmark_commands':6})
assert len(native['comparisons'])==3 and all(row['result']=='PASS' for row in native['comparisons'])
assert fuzz['status']=='PASS' and fuzz['build_source_sha']==FROZEN and fuzz['build_source_dirty'] is False
assert fuzz['android_jni_instrumentation']=='NOT_RUN'
checked(fuzz['library']['path'],fuzz['library']['sha256']);checked(fuzz['buildstamp'],fuzz['buildstamp_sha256']);checked(fuzz['compiler_identity']['path'],fuzz['compiler_identity']['sha256'])
for item in fuzz['source_files']:
 blob=subprocess.check_output(['git','show',FROZEN+':'+item['path']],cwd='/workspace/agent-workspaces/100')
 assert len(blob)==item['bytes'] and hashlib.sha256(blob).hexdigest()==item['sha256']
targets=[]
for step in fuzz['steps']:
 checked(step['log'],step['log_sha256']);assert step['status']=='PASS' and step['exit_code']==0
 if 'target' in step:
  checked(step['command'][0],step['binary_sha256'])
  assert step['build_source_sha']==FROZEN and step['completed_runs']==[10000] and step['requested_runs']==10000 and step['budget_completion']=='runs'
  assert not step['artifacts']
  targets.append({key:step[key] for key in ('target','completed_runs','requested_runs','time_budget_seconds','reported_seconds','exit_code','status','binary_sha256','library_sha256','log','log_sha256')})
assert {row['target'] for row in targets}=={'numeric','checkpoint','exports'}
provider=Path('/workspace/campaign/evidence/082/provider068-handoff/evidence.json');provider_data=json.loads(provider.read_text())
assert provider_data['source_sha']=='0e85e76bdc38d0df80c627933a96a5d868c867fe' and provider_data['parser_checks']==228 and provider_data['status']=='PASS'
for name,sha in provider_data['evidence'].items():checked(provider.parent/name,sha)

p=Path('/workspace/agent-workspaces/081/docs/agents/status/081.md');old=p.read_text();match=re.search(r'```json\s*\n(.*?)\n```',old,re.S);receipt=json.loads(match[1])
assert receipt['agent']=='081'
receipt['historical_implementation_evidence']={key:receipt[key] for key in ('tested_source_sha','tested_tree_dirty','command','environment','dataset_identity','evidence','artifact_sha256')}
receipt['historical_implementation_matrix']=receipt['matrix']
receipt.update(tested_source_sha=FROZEN,tested_tree_dirty=False,command='cd /workspace/campaign/frozen100; source /workspace/campaign/evidence/100/qa-tools-fixed/activate.sh; python tools/qa/native_regression/run.py --clang /workspace/.tools/native-qa/llvm/usr/bin/clang++-19 --output /workspace/campaign/evidence/100/native-final',environment='Linux x86_64 glibc2.41; GCC14.2.0/Clang19.1.7; pinned095 QA CPython3.12.14 environment; separate Release/Debug ASan+UBSan builds with -Werror',evidence=str(NATIVE),artifact_sha256=digest(NATIVE),matrix=matrix)
receipt['frozen_integration_candidate']={'source_sha':FROZEN,'source_dirty':False,'relationship':'Agent100 frozen integration candidate; supplier histories are not merged into this081 delivery branch','aggregate_result':'FAIL','parity_comparisons':native['comparisons'],'limits':{'physical_device':'NOT_RUN','android_jni_runtime':'NOT_RUN','hosted_workflow':'NOT_RUN'}}
command=receipt['command']
receipt['acceptance_results'] += [
 {'criterion':'Final frozen86 GCC/Clang Release and sanitizer native CTests complete','result':'PASS','command':command,'source_sha':FROZEN,'artifact_sha256':digest(NATIVE),'environment':receipt['environment'],'dataset_identity':receipt['dataset_identity'],'detail':'Four separate configurations each pass11/11 CTests (44/44 total); all retained compiler/binary/log/artifact hashes verified. Native CTest success does not waive Python process failures.','evidence':str(NATIVE)},
 {'criterion':'Final frozen86 complete Release Python suites and benchmark commands pass for both compilers','result':'PASS','command':command,'source_sha':FROZEN,'artifact_sha256':digest(NATIVE),'environment':receipt['environment'],'dataset_identity':'Actual integrated764-test suite plus labeled synthetic analytic scenes and existing real Middlebury commands','detail':'GCC Release764 passed/0 failed/0 errors/0 skipped; Clang Release764 passed/0 failed/0 errors/0 skipped. All8 Python commands per Release configuration exit0.','evidence':str(NATIVE)},
 {'criterion':'Final frozen86 sanitized complete Python suites and process-exit memory acceptance','result':'FAIL','command':command,'source_sha':FROZEN,'artifact_sha256':digest(NATIVE),'environment':'GCC and Clang ASan halt_on_error=1 detect_leaks=1; UBSan halt_on_error=1 print_stacktrace=1; no suppression or disabled leak checks','dataset_identity':'Each actual integrated764-test suite plus numerical and six benchmark commands','detail':'Each sanitizer suite records740 passed,24 failed,0 errors,0 skipped. All8 Python commands per sanitizer configuration exit1 and contain LeakSanitizer diagnostics. Both sanitizer configurations and full matrix remain FAIL; the24 failed tests are retained as failures.','evidence':str(NATIVE)},
 {'criterion':'Final frozen86 deterministic numerical parity comparisons preserve required tolerances','result':'PASS','command':command,'source_sha':FROZEN,'artifact_sha256':digest(NATIVE),'environment':receipt['environment'],'dataset_identity':'Ten seeded synthetic analytic scenes per configuration; exact/noisy20-outlier variants','detail':'All3 comparisons PASS against GCC Release with existing rtol1e-5/atol1e-6; no tolerance change.','evidence':str(NATIVE)}]
receipt['remaining_work']=[
 'Resolve sanitized Python process LeakSanitizer failures and each suite\'s24 failed tests; rerun all required commands on an exact successor candidate with leak detection enabled. Current frozen86 full matrix remains FAIL.',
 'Complete actual hosted081 execution with approved095 host/QA resolutions; cold portable Blender and hosted execution remain NOT_RUN here. Legacy004 input advisory remains its owner request.',
 'Physical/device/JNI runtime qualification remains separate and NOT_RUN; no host numerical, parser or fuzz result supplies those passes.'
]
receipt['next_action']='Agent100 may merge only this receipt/manifest metadata; retain frozen86 source and artifact identities, Release passes, sanitizer failures and separate pending runtime gates.'
note='''\n\nFrozen Agent100 final matrix: source86df647bcdba4b110ad36dfb6ce572adb4261462 is\na separate clean integrated candidate. Native CTests44/44 and both Release\n764-test suites pass; both sanitizer suites retain740 passes and24 failures,\nand all8 Python commands per sanitizer configuration fail with LeakSanitizer.\nThe full matrix is FAIL. Comparisons3/3 pass. This metadata-only handoff\npreserves prior implementation evidence and never implies hardware/JNI success.\n'''
p.write_text(old[:match.start(1)]+json.dumps(receipt,indent=2)+old[match.end(1):]+note)

p=Path('/workspace/agent-workspaces/082/docs/agents/status/082.md');receipt=json.loads(p.read_text());assert receipt['agent']=='082'
receipt['tested_source_sha']=FROZEN
receipt['frozen_integration_candidate']={'source_sha':FROZEN,'source_dirty':False,'relationship':'Agent100 frozen integration candidate; no provider or integration histories merged into082 branch','evidence':str(FUZZ),'artifact_sha256':digest(FUZZ),'status':'PASS','scope':'Three bounded synthetic native libFuzzer/ASan/UBSan smoke targets only','source_fingerprint':fuzz['source_fingerprint'],'buildstamp':fuzz['buildstamp'],'buildstamp_sha256':fuzz['buildstamp_sha256'],'compiler_identity':fuzz['compiler_identity'],'library':fuzz['library'],'targets':targets,'android_jni_instrumentation':'NOT_RUN','physical_device':'NOT_RUN','integrated_viewer_runtime':'NOT_RUN'}
receipt['acceptance_results'].append({'criterion':'Final frozen86 exact-build native sanitizer fuzz smoke completes all declared runs','result':'PASS','command':'cd /workspace/campaign/frozen100; python tools/qa/fuzz/run.py --compiler /workspace/.tools/native-qa/llvm/usr/bin/clang++-19 --runs 10000 --seconds 30 --output /workspace/campaign/evidence/100/fuzz-final','source_sha':FROZEN,'artifact_sha256':digest(FUZZ),'environment':'Linux x86_64 Clang19.1.7 ASan/UBSan/libFuzzer; seed82019; each target10000 runs within30s, timeout3s, rss1024MiB, malloc256MiB, max_len16384; no sanitizer suppressions','dataset_identity':'Generated synthetic numeric/checkpoint/export seeds; no physical photographs or device qualification','detail':'numeric,checkpoint,exports each complete10000 runs and exit0; no crash artifacts. Compiler/library/binary/buildstamp/log hashes and all frozen86 source-file hashes independently verified. Existing actual068228 host parser checks remain a separate supplying-source result.','evidence':str(FUZZ)})
receipt['retained_provider068_evidence']={'source_sha':provider_data['source_sha'],'evidence':str(provider),'artifact_sha256':digest(provider),'parser_checks':228,'status':'PASS','scope':'Actual published production host parser with synthetic canonical061 formats; supplied source unmerged into100, Android rendering/lifecycle and physical results NOT_RUN'}
receipt['remaining_work']=[
 'Execute actual bounded Android JNI instrumentation on an identified authorized target with exact source/APK/installed-package evidence; compilation and native fuzz smoke do not execute Android JNI.',
 'Integrate reviewed068 loader and execute actual device parser/render/lifecycle corpus; retained228 host parser assertions do not qualify the integrated viewer.',
 'Physical/device qualification remains NOT_RUN; keep scoped synthetic fuzz and host parser evidence separate from generic/full product acceptance.'
]
receipt['next_action']='Agent100 may merge only receipt/manifest metadata; retain frozen86 bounded native fuzz PASS and separate068228 host parser PASS while actual JNI/device/viewer gates remain NOT_RUN.'
p.write_text(json.dumps(receipt,indent=2)+'\n')
report={'schema':1,'source_sha':FROZEN,'metadata_only':True,'native_evidence_sha256':digest(NATIVE),'fuzz_evidence_sha256':digest(FUZZ),'matrix':matrix,'comparisons':native['comparisons'],'fuzz_targets':targets,'retained_provider068_sha256':digest(provider),'all_referenced_hashes_verified':True,'scope':'Retained artifacts verified without rerunning tests or claiming pending runtime gates'}
p=Path('/workspace/campaign/evidence/081/frozen86-metadata-verification.json');p.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
print(json.dumps({'verified_record':str(p),'native_sha256':digest(NATIVE),'fuzz_sha256':digest(FUZZ),'ctest_passes':44,'release_pytest_passes_per_compiler':764,'sanitizer_pytest_per_compiler':{'passed':740,'failed':24},'fuzz_runs_per_target':10000,'metadata_files_changed':['081/status','082/status']}))
