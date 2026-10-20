from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,subprocess,xml.etree.ElementTree as ET
ROOT=Path('/workspace/campaign/frozen100'); OUT=Path('/workspace/campaign/evidence/099/final-86'); BASE=Path('/workspace/campaign/evidence')
SOURCE='86df647bcdba4b110ad36dfb6ce572adb4261462'
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def load(p):return json.loads(Path(p).read_text())
def save(p,x):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,indent=2,allow_nan=False)+'\n')
def blob(p):
 p=Path(p);s=sha(p);dest=OUT/'blobs'/s;dest.parent.mkdir(parents=True,exist_ok=True)
 if not dest.exists():dest.write_bytes(p.read_bytes())
 assert sha(dest)==s
 return {'path':str(p),'sha256':s,'bytes':p.stat().st_size,'immutable_blob':str(dest)}
now=datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
summary=load(BASE/'100/android-final/summary.json'); apk=Path(summary['artifacts']['apk']['path']);testapk=Path(summary['artifacts']['test_apk']['path'])
assert subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip()==SOURCE
assert not subprocess.check_output(['git','-C',str(ROOT),'status','--porcelain'],text=True).strip()
assert sha(apk)==summary['artifacts']['apk']['sha256'];assert sha(testapk)==summary['artifacts']['test_apk']['sha256']
policy=ROOT/'tools/release/qualification/policy-v1.json'; applicability=load(ROOT/'docs/agents/integration/gate-applicability.json')
assert sha(policy)==applicability['policy_sha256'] and len(applicability['gates'])==18 and all(x['applicability']=='REQUIRED' for x in applicability['gates']) and not applicability['waivers']
candidate={'schema':1,'source_sha':SOURCE,'artifact_sha256':sha(apk),'run_id':'frozen100-86df647b-initial-release-v1','created_at':'2026-10-20T09:35:00Z','policy_sha256':sha(policy),'protocols':{k:sha(ROOT/p) for k,p in [('geometry','benchmarks/quality_protocols/geometry.json'),('texture','benchmarks/quality_protocols/texture.json'),('resources','benchmarks/resource-protocols/protocol.json')] if (ROOT/p).is_file()},'scenes':[],'devices':[]}
save(OUT/'candidate.json',candidate)
inputs=[]
def evidence(name,paths,scope,observations):
 snapshots=[blob(p) for p in paths];inputs.extend(snapshots)
 p=OUT/'derivations'/f'{name}.json';save(p,{'schema':1,'derived_by':'099 independent final review','recorded_at':now,'source_sha':SOURCE,'candidate_apk_sha256':sha(apk),'scope':scope,'input_snapshots':snapshots,'observations':observations});return blob(p)['sha256']
def record(gate,agent,status,paths,scope,metrics,blockers,command):
 ev=evidence(gate,paths,scope,metrics);r={'schema':1,'agent':agent,'gate':gate,'source_sha':SOURCE,'artifact_sha256':sha(apk),'run_id':candidate['run_id'],'recorded_at':now,'command':command,'environment':{'kind':'host','identity':'Linux x86_64 cloud; immutable owner outputs independently reviewed by099'},'dataset_identity':{'id':'no-physical-corpus','kind':'none','sha256':hashlib.sha256(b'no-physical-corpus').hexdigest()},'status':status,'blockers':blockers,'metrics':metrics,'evidence_sha256':ev,'tool_source_sha':SOURCE};save(OUT/'records'/f'{gate}.json',r)
def counts(n=0,f=0):return {'executed_tests':n,'failed_tests':f,'skipped_tests':0,'disabled_tests':0}
# Read exact actual XML counts rather than inferring from suite labels.
native=load(BASE/'100/native-final/evidence.json');assert native['source_sha']==SOURCE and native['result']=='FAIL' and not native['dirty_tree']
rows=[];tot=fail=0;nativepaths=[BASE/'100/native-final/evidence.json']
for cfg in native['configurations']:
 p=BASE/'100/native-final'/cfg['name']/'pytest.xml';s=list(ET.parse(p).getroot().iter('testsuite'));n=sum(int(x.get('tests','0')) for x in s);f=sum(int(x.get('failures','0'))+int(x.get('errors','0')) for x in s);tot+=n;fail+=f;rows.append({'configuration':cfg['name'],'tests':n,'failed':f});nativepaths.append(p)
record('native','081','FAIL',nativepaths,'Actual four-compiler/sanitizer matrix; each pytest invocation is counted separately; CTest and fuzz pass cannot waive LeakSanitizer failures.',{**counts(tot,fail),'sanitizers_passed':0,'ctest_executed':1,'pytest_executed':1,'configuration_count':4,'ctest_passed_total':44},['Both sanitized Python-hosted matrices fail with24 failed test cases each; strict LSan exit1 retained.'],'python -m tools.qa.native_regression.run --source /workspace/campaign/frozen100 (exact argv retained in backing evidence)')
assert summary['source_sha']==SOURCE and summary['status']=='PASS' and summary['jvm']['tests']==35 and not any(summary['jvm'][x] for x in ['failures','errors','skipped'])
record('android_build','091','PASS',[BASE/'100/android-final/summary.json',BASE/'100/android-final/gradle.log',BASE/'100/android-final/package.json',BASE/'100/android-final/privacy.json',BASE/'100/android-final/attribution.json'],'Exact debug app/test APK compilation,35 actual JVM tests and lint; no instrumentation, minification, production signing or physical runtime is inferred.',{**counts(35),'compile_passed':1,'lint_passed':1,'jvm_tests_passed':1},[],'flock /workspace/campaign/android.lock python /workspace/campaign/gradle.py :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug')
suitepath=BASE/f'096/final-{SOURCE}/suite.json';suite=load(suitepath);assert suite['source_sha']==SOURCE and not suite['source_dirty'] and suite['status']=='PASS'
securitypaths=[suitepath]
for item in suite['reports']:
 p=Path(item['report']);assert sha(p)==item['sha256'];d=load(p);assert d['source_sha']==SOURCE and d['status']=='PASS' and item['finding_counts']['active']==0;securitypaths.append(p)
record('security','096','PASS',securitypaths,'Scoped Python host static/secrets checks and three approved Python dependency graphs;9 completed scanner executions across3 graphs. Not native/JNI parser security or Android/JVM/native CVE qualification.',{**counts(9),'static_executed':1,'secrets_executed':1,'dependencies_executed':1,'active_findings':0,'approved_graphs':3,'source_files':suite['source_files_count']},[],'python tools/security/scan.py --root /workspace/campaign/frozen100 --requirements <approved base|qa|security lock> (exact inputs in backing reports)')
# Explicit missing host gates are backed by actual frozen applicability and source facts.
record('feature_wiring','100','NOT_RUN',[ROOT/'docs/agents/integration/gate-applicability.json',ROOT/'docs/agents/integration/deliveries.json'],'Required001-080 product wiring not accepted by branch presence or081-100 QA tooling.',counts(),['No complete immutable accepted001-080 feature/interface wiring evidence; host QA tools and compiled baseline do not establish full product wiring.'],'099 independent review of frozen applicability and delivery ledger')
# Legacy vulnerable declaration is verified byte-for-byte against its audited input, not silently retargeted.
legacy=BASE/'096/legacy-direct-final-fail.json';included=BASE/'096/legacy-included-final-fail.json'
assert 'pytest==8.4.1' in (ROOT/'tools/requirements.txt').read_text()
record('dependencies','095','FAIL',[ROOT/'tools/requirements.txt',ROOT/'tools/requirements-real-benchmark.txt',legacy,included],'Frozen owner004 legacy entrypoints still contain the previously actually audited pytest8.4.1 PYSEC-2026-1845 declaration. Approved095 locks pass separately; full Android/JVM/native graph and distribution rights remain incomplete.',{**counts(2,2),'unresolved_vulnerabilities':1},['Legacy direct/included pytest8.4.1 declaration remains vulnerable; full runtime license/CVE closure is not supplied.'],'099 compare frozen dependency entrypoints with096 actual direct/include pip-audit failure reports')
record('release_package','091','NOT_RUN',[BASE/'100/release-final/release-integration-summary.json'],'Actual R8/AAB85-APK checks pass only on a separately identified004 overlay source and different release artifact, not this frozen86 debug APK.',counts(),['Frozen86 debug candidate has no exact-source R8/AAB release-package evidence; separate0a4d910 overlay cannot qualify this artifact.'],'099 compare actual release overlay source and artifact hashes against candidate86 debug identity')
record('signing','091','BLOCKED_EXTERNAL',[BASE/'100/release-final/release-integration-summary.json'],'Dry-run signing and debug artifacts are not production identity.',counts(),['Production signing identity and authorized distribution license are unavailable; debug APK is not a release signature.'],'099 review productionSigning and firstPartyDistributionLicense in actual package summary')
# Out-of-matrix observations stay supplemental: no fake physical scene/device IDs.
texture=BASE/'100/texture-baseline/actual-texture-receipt.json';tex=load(texture);assert tex['source_sha']==SOURCE and tex['independent_uv_gate']=='FAIL'
supp=[{'id':'exact_candidate_texture_uv','status':'FAIL','evidence':blob(texture),'reason':'23 UV-overlap pairs on actual frozen native engine; held-out NOT_RUN and physical BLOCKED_EXTERNAL. Physical texture gate remains blocked rather than falsely admitting host stereo as a physical360 bundle.'},{'id':'external_provider064','status':'FAIL','evidence':blob(BASE/'090/incoming-exact/incoming-handoff-results.json'),'reason':'064 analytic supplied normal fails; provider source identities differ from frozen100 and do not become candidate physical interoperability evidence.'},{'id':'fuzz','status':'PASS','evidence':blob(BASE/'100/fuzz-final/evidence.json'),'reason':'Three10k actual native parser fuzz targets; supplemental to full native/sanitizer gate.'},{'id':'separate_release_overlay','status':'PASS_LIMITED','evidence':blob(BASE/'100/release-final/release-integration-summary.json'),'reason':'Different source0a4d910 and release artifact; never qualifies frozen86 debug gate.'}]
save(OUT/'input-index.json',{'schema':1,'source_sha':SOURCE,'artifact_sha256':sha(apk),'test_apk_sha256':sha(testapk),'recorded_at':now,'candidate':blob(OUT/'candidate.json'),'policy':blob(policy),'applicability':blob(ROOT/'docs/agents/integration/gate-applicability.json'),'native_pytest_counts':rows,'record_derivation':'Owner-numbered C16 records independently derived by099 from supplied actual owner outputs. These are local review records, not signed owner attestations. Scanner executions count as completed checks; none are presented as device tests.','inputs':inputs,'supplemental_observations':supp})
print(json.dumps({'candidate':str(OUT/'candidate.json'),'records':len(list((OUT/'records').glob('*.json'))),'actual_source':SOURCE,'apk_sha256':sha(apk),'test_apk_sha256':sha(testapk),'native_pytest_counts':rows},indent=2))
