from pathlib import Path
import concurrent.futures,subprocess,json,yaml,hashlib
BASE=Path('/workspace/campaign-061-080');proof=json.loads((BASE/'c16-matched-code-proof.json').read_text())
def audit(role):
 root=Path('/workspace/agent-work')/role
 def git(*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
 head=git('rev-parse','HEAD');branch=git('branch','--show-current')
 assert git('ls-remote','--heads','origin','refs/heads/'+branch).split()[0]==head
 assert not git('status','--porcelain','--untracked-files=no')
 assert set(git('diff','--name-only',proof[role]['role_head_before_receipt'],head).splitlines())=={f'docs/agents/status/{role}.md','SOURCE_MANIFEST.sha256'}
 d=yaml.safe_load((root/f'docs/agents/status/{role}.md').read_text())
 required=['agent','role','branch','campaign_base','implementation_commits','state','owned_paths_changed','interfaces_published','integration_requests','acceptance_results','remaining_work','external_blockers','next_action']
 assert all(k in d for k in required) and d['agent']==role and d['state']=='READY_FOR_INTEGRATION'
 assert all(isinstance(x,str) for x in d['interfaces_published']+d['integration_requests'])
 for p,v in proof[role]['files'].items():
  blob=subprocess.run(['git','-C',str(root),'rev-parse','--verify',f'{head}:{p}'],capture_output=True,text=True)
  observed=blob.stdout.strip() if blob.returncode==0 else None
  assert observed==v['combined_blob_oid'],(role,p)
 rows=[x for x in d['acceptance_results'] if isinstance(x,dict)]
 qualifying=[x for x in rows if x.get('evidence_id')=='c16-combined-android-a18']
 assert len(qualifying)==1 and qualifying[0]['result']=='PASS'
 assert any(x['result']=='FAIL' and 'Python regression' in x['criterion'] and x['failed']==7 for x in rows)
 assert any(x['result']=='NOT_RUN' and 'physical-device' in x['criterion'] for x in rows)
 manifest=subprocess.check_output(['python','/workspace/campaign-061-080/dependencies/094/source_manifest.py','verify','--source','index','--repo',str(root)],text=True)
 return {'agent':role,'branch':branch,'head':head,'remote_verified':True,'receipt_yaml':'PASS','manifest':json.loads(manifest),'metadata_only':True,'matched_code_paths':len(proof[role]['files']),'source_blobs_equal_combined':True,'state':d['state']}
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(audit,proof))
report={'combined_source':'a18aa6eb6d8fac17a8db6f1b489f7f04ebb95502','state':'PASS','agents':results,'matched_code_proof_sha256':hashlib.sha256((BASE/'c16-matched-code-proof.json').read_bytes()).hexdigest()}
(BASE/'c16-final-receipts.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'state':'PASS','roles':len(results),'source_paths_verified':sum(x['matched_code_paths'] for x in results),'heads':{x['agent']:x['head'] for x in results}},indent=2))
