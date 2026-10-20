from pathlib import Path
import subprocess,json,hashlib,yaml,re,sys
BASE=Path('/workspace/campaign-061-080');WT=Path('/workspace/agent-work');GIT=BASE/'combined'
RESULT=BASE/'final-validation/a18aa6eb6d8f-buildscript-relative/result.json'
QUAL=json.loads(RESULT.read_text());SOURCE=QUAL['source_commit'];CAMPAIGN='ad3029193f304c70931b94ea7a0df90b69d84b07'
ROLES=[f'{n:03}' for n in range(61,81) if n not in [67,77,80]]
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args])
def oid(root,ref,path):
 result=subprocess.run(['git','-C',str(root),'rev-parse','--verify',f'{ref}:{path}'],capture_output=True,text=True)
 return result.stdout.strip() if result.returncode==0 else None
inputs={x['agent']:x['source_sha'] for x in QUAL['planned_inputs']}
proof={}
for role in ROLES:
 root=WT/role;head=git(root,'rev-parse','HEAD').decode().strip()
 if git(root,'status','--porcelain','--untracked-files=no').strip():raise Exception('Tracked worktree dirty '+role)
 changed=git(root,'diff','--name-only',CAMPAIGN,head).decode().splitlines()
 runtime=[p for p in changed if not p.startswith('docs/') and not p.lower().endswith('.md') and p!='SOURCE_MANIFEST.sha256']
 files={p:{'role_blob_oid':oid(root,head,p),'combined_blob_oid':oid(GIT,SOURCE,p)} for p in runtime}
 mismatches=[p for p,v in files.items() if v['role_blob_oid']!=v['combined_blob_oid']]
 proof[role]={'role_head_before_receipt':head,'planned_input_sha':inputs[role],'combined_source_sha':SOURCE,'files':files,'mismatches':mismatches,'state':'PASS' if not mismatches else 'FAIL'}
 print(role,head[:12],len(files),'MATCH' if not mismatches else repr(mismatches),flush=True)
(BASE/'c16-matched-code-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
if any(p['mismatches'] for p in proof.values()):sys.exit(2)

def load_receipt(raw):
 if raw.startswith('---'):
  parts=raw.split('---',2)
  return yaml.safe_load(parts[1]) or {},parts[2].strip()
 try:
  data=yaml.safe_load(raw)
  if isinstance(data,dict):return data,''
 except yaml.YAMLError:pass
 for boundary in reversed([m.start() for m in re.finditer(r'\n\s*\n',raw)]):
  try:
   data=yaml.safe_load(raw[:boundary])
   if isinstance(data,dict) and 'agent' in data:return data,raw[boundary:].strip()
  except yaml.YAMLError:pass
 raise ValueError('Unrecognized prior receipt')
def arr(value):return value if isinstance(value,list) else ([] if value is None else [value])
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
ENV='Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1; Android29 arm64-v8a/x86_64 cross-compilation; no device execution'
PYTEST_PATH=RESULT.parent/'pytest-result.json';PYTEST=json.loads(PYTEST_PATH.read_text())
ORIGINAL=BASE/'final-validation/a18aa6eb6d8f/result.json';original=json.loads(ORIGINAL.read_text())
assert QUAL['state']=='PASS' and QUAL['jvm_tests']['tests']==214 and QUAL['jvm_tests']['skipped']==0
assert PYTEST['state']=='FAIL' and PYTEST['passed']==176 and PYTEST['totals']['failures']==7 and PYTEST['totals']['skipped']==0
assert original['state']=='FAIL'
app=QUAL['artifacts']['app/build/outputs/apk/debug/app-debug.apk']['sha256']
testapk=QUAL['artifacts']['app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']['sha256']
roles_plan=(WT/'071'/'PRODUCTION_100_AGENT_EXECUTION_PLAN.md').read_text()
proof_file=BASE/'c16-matched-code-proof.json';proof_sha=sha(proof_file)
for role in ROLES:
 root=WT/role;path=root/f'docs/agents/status/{role}.md'
 data,notes=load_receipt(path.read_text())
 data['agent']=role
 roster=re.search(r'## Agent '+role+r'\n\n\*\*Role:\*\* ([^\n]+)',roles_plan)
 data['role']=roster.group(1)
 data['branch']=git(root,'branch','--show-current').decode().strip()
 data['campaign_base']=CAMPAIGN
 data['implementation_commits']=arr(data.get('implementation_commits')) or git(root,'rev-list','--reverse',CAMPAIGN+'..HEAD').decode().splitlines()
 prior_state=str(data.get('state','WORKING'))
 if prior_state=='WORKING' and role in ['072','073','074','075','079']:data['state']='READY_FOR_INTEGRATION'
 elif prior_state=='DONE':raise Exception('Unexpected DONE receipt '+role)
 data.setdefault('state','READY_FOR_INTEGRATION')
 data['owned_paths_changed']=arr(data.get('owned_paths_changed')) or git(root,'diff','--name-only',CAMPAIGN,'HEAD').decode().splitlines()
 data['interfaces_published']=arr(data.get('interfaces_published'))
 data['integration_requests']=arr(data.get('integration_requests'))
 for request in [str(BASE/'combined/docs/agents/requests/080-to-094.md'),str(BASE/'combined/docs/agents/requests/080-to-053.md')]:
  if request not in data['integration_requests']:data['integration_requests'].append(request)
 if notes:data['prior_evidence_notes']=notes
 prior_rows=arr(data.get('acceptance_results'))
 for row in prior_rows:
  if isinstance(row,dict):
   for key,value in [('criterion','Prior role-specific acceptance evidence'),('result','NOT_RUN'),('command','See preserved prior evidence'),('source_sha',data.get('source_candidate') or data.get('candidate_source_sha') or proof[role]['planned_input_sha']),('artifact_sha256',None),('environment',ENV),('dataset_identity',None),('evidence','See preserved prior receipt')]:row.setdefault(key,value)
   if row.get('result')=='NOT_RUN' and 'combined' in str(row.get('criterion','')).lower() and any(x in str(row.get('criterion','')).lower() for x in ['android','apk','lint']):
    row['historical_pending_gate']='Build/JVM/lint portion superseded by c16-combined-android-a18; device/runtime/owner wiring remains open.'
 def row(criterion,result,command,evidence,artifact=None,**extra):
  return dict(criterion=criterion,result=result,command=command,source_sha=SOURCE,role_input_sha=proof[role]['planned_input_sha'],artifact_sha256=artifact,environment=ENV,dataset_identity='Committed regression/unit fixtures and packaged Android artifacts; no physical scans or device accuracy qualification',evidence=evidence,**extra)
 android=row('C16 combined app/test-APK compilation, JVM regression, lint, offline and16KB packaging with explicit external verification adapters','PASS',' '.join(QUAL['commands'][0]['command'])+'; '+' '.join(QUAL['commands'][1]['command']),str(RESULT),app,
  evidence_id='c16-combined-android-a18',test_apk_sha256=testapk,result_json_sha256=sha(RESULT),jvm_tests={'tests':214,'failures':0,'errors':0,'skipped':0},lint={'errors':0,'warnings':28},packaging={'internet_permission':False,'elf_libraries_16kb_checked':16,'zip_alignment_16kb':True,'new_native_symbols_in_both_abis':7},
  verification_adapters=QUAL['verification_adapters'],adapter_scope='External owner004 URI/MessageDigest import fix plus CMake registration of seven native sources and exact Filament/gltfio1.56.0 dependencies. No committed source changed; this is not an unadapted standalone build pass.',matched_code_proof={'path':str(proof_file),'sha256':proof_sha,'role_head':proof[role]['role_head_before_receipt'],'changed_non_doc_nonmanifest_paths':len(proof[role]['files']),'all_role_blobs_equal_combined':True},limitations=QUAL['limitations'])
 failed=row('Original immutable combined attempt without the owner004 app-build-script import verification adapter','FAIL',' '.join(original['commands'][0]['command']),str(ORIGINAL),None,result_json_sha256=sha(ORIGINAL),failure='Owner004 build-script java DSL accessor shadows java.net.URI and java.security.MessageDigest package qualifiers; preserved original failure, external adapter only in separate qualifying attempt.')
 pytest=row('Full combined Python regression suite; failures retained without deselection, xfail or skips','FAIL',' '.join(PYTEST['command']['command']),str(PYTEST_PATH),PYTEST['xml']['sha256'],passed=176,failed=7,skipped=0,result_json_sha256=sha(PYTEST_PATH),failed_cases=[x['nodeid'] for x in PYTEST['failed_cases']],failure_classification='Six mapped legacy UI/lifecycle source-literal expectations require094/053 semantic migration; seventh M18 depth-resume identity expectation remains unclassified and requires094 review. No test weakened or failure converted to success.',owner_requests=[str(BASE/'combined/docs/agents/requests/080-to-094.md'),str(BASE/'combined/docs/agents/requests/080-to-053.md')])
 new_rows=[android,failed,pytest]
 if role in ['061','066']:
  native_log=BASE/'combined-final-native.log'
  new_rows.append(row('Combined host native regression;17 CTest cases','PASS','cmake --build /workspace/campaign-061-080/native-harness/build; ctest --test-dir /workspace/campaign-061-080/native-harness/build --output-on-failure',str(native_log),PYTEST['host_library']['sha256'],tests=17,failures=0,log_sha256=sha(native_log),input_hashes=str(BASE/'native-final-source.json'),limitation='Host execution only; no JNI routing, physical ARM64 execution or accuracy claim.'))
 new_rows.append(row('Required physical-device, manual accessibility/adaptive/locale, process-death and measured runtime acceptance','NOT_RUN','Run role-specific080/100 integrated device and manual acceptance matrix','Prior required device/manual/owner gates remain open; Android build does not execute instrumentation.'))
 data['acceptance_results']=prior_rows+new_rows
 data['remaining_work']=arr(data.get('remaining_work'))
 data['external_blockers']=arr(data.get('external_blockers'))
 for item in ['Owner004 must adopt the tested build-script imports/native registration; owner069 Filament dependencies remain external verification adapters in this evidence.','Owners094/053 must resolve the full combined pytest7 failures, including the unclassified M18 depth identity expectation, without weakening protected behavior.','Required physical/manual/runtime gates remain NOT_RUN; compile/test-APK packaging does not establish instrumentation or device success.']:
  if item not in data['remaining_work']:data['remaining_work'].append(item)
 if role in ['072','073','074','079','071']:
  data['external_blockers'].append('Receiving001/002/005/006/007/008/023 owners must install real transactional persistence, fenced executor and published-manifest/SAF operation providers where required; unsupported capabilities remain disabled.')
 data['external_blockers'].append('Agent100 must integrate tested source plus receiving-owner fixes; unadapted build and full pytest are not green.')
 data['next_action']='Integrate exact tested implementation with receiving-owner build/persistence/executor/publication fixes; resolve preserved pytest failures and run outstanding role-specific device/manual gates. Receipt-only metadata delivery is not DONE.'
 data['c16_evidence_scope']='All changed non-doc/nonmanifest branch files match exact a18 blobs. This update changes only this receipt and the canonical staged-source manifest.'
 path.write_text(yaml.safe_dump(data,sort_keys=False,width=112,allow_unicode=True))
 parsed=yaml.safe_load(path.read_text())
 required=['agent','role','branch','campaign_base','implementation_commits','state','owned_paths_changed','interfaces_published','integration_requests','acceptance_results','remaining_work','external_blockers','next_action']
 assert all(k in parsed for k in required) and parsed['state']!='DONE'
 print('UPDATED',role,parsed['state'],len(parsed['acceptance_results']),flush=True)
