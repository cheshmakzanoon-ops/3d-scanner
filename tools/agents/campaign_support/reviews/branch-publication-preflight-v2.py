from pathlib import Path
import concurrent.futures,datetime,hashlib,json,subprocess
root=Path('/workspace/agent-workspaces/100');out=Path('/workspace/campaign/evidence/100');source='69e02e60c376f1f6b2cae13e38e5b8f4783aab03'
def command(*args,check=True):return subprocess.run(['git','-C',str(root),*args],text=True,capture_output=True,check=check)
def show(sha,path):return command('show',sha+':'+path).stdout
def digest(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def ancestor(a,b):return command('merge-base','--is-ancestor',a,b,check=False).returncode==0
code=show(source,'tools/agents/campaign.py');plan=show(source,'PRODUCTION_100_AGENT_EXECUTION_PLAN.md');ns={'__file__':str(root/'tools/agents/campaign.py'),'__name__':'review_campaign'};exec(compile(code,'69e02-campaign.py','exec'),ns)
rows=ns['roster'](plan);base=json.loads(show(source,'docs/agents/integration/campaign-base.json'))['campaign_base'];ledger=json.loads(show(source,'docs/agents/integration/deliveries.json'))['deliveries'];latest={r['agent']:r['source_sha'] for r in ledger}
remote_path=out/'branch-publication-preflight-v2-ls-remote.txt';remotes={ref.removeprefix('refs/heads/'):sha for sha,ref in (line.split() for line in remote_path.read_text().splitlines())}
moving={'088','098','099','100'};known_mixed={'085':('0aba07179bd505bc880dc2ba76061d7abcc94ffb',13),'094':('bf3c6ea2b0d8043a8ddb882d487473733de7228a',365)}
def receipt(row,sha):
 result=command('show',sha+':'+row['receipt'],check=False)
 if result.returncode:return {'state':'MISSING_RECEIPT','errors':['Missing receipt'],'acceptance_results':[]}
 record=ns['receipt_record'](result.stdout);errors=ns['receipt_checks'](result.stdout)+ns['receipt_freshness'](root,result.stdout,row['agent'],sha)
 return {'state':ns['receipt_state'](result.stdout),'errors':errors,'blob_sha':command('rev-parse',sha+':'+row['receipt']).stdout.strip(),'sha256':hashlib.sha256(result.stdout.encode()).hexdigest(),'implementation_commits':record.get('implementation_commits'),'acceptance_results':ns['acceptance_rows'](record)}
def validate(row):
 a=row['agent'];remote=remotes.get(row['branch']);supply=source if a=='100' else latest.get(a)
 r={'agent':a,'role':row['role'],'branch':row['branch'],'source_moving':a in moving,'remote_sha':remote,'actual_integrated_supplying_sha':supply,'validator_source_sha':source}
 if not remote or not supply:return r|{'publication_status':'MISSING_SOURCE','errors':['Remote or supplying source missing']}
 for sha in (remote,supply):
  if command('cat-file','-e',sha+'^{commit}',check=False).returncode:return r|{'publication_status':'OBJECT_UNAVAILABLE','errors':['Object unavailable '+sha]}
 r.update(remote_tree_sha=command('rev-parse',remote+'^{tree}').stdout.strip(),supplying_tree_sha=command('rev-parse',supply+'^{tree}').stdout.strip(),supplying_is_ancestor_of_remote=ancestor(supply,remote),supplying_is_ancestor_of_integrator=ancestor(supply,source),remote_is_ancestor_of_integrator=ancestor(remote,source),remote_ownership_errors=ns['ownership'](root,rows,a,base,remote),supplying_ownership_errors=ns['ownership'](root,rows,a,base,supply),remote_receipt=receipt(row,remote),supplying_receipt=receipt(row,supply))
 paths=command('diff','--no-renames','--name-only',supply,remote).stdout.splitlines();r['supplying_to_remote_delta_paths']=paths
 errors=[]
 if r['supplying_ownership_errors']:errors.append('Supplying source has foreign paths')
 if not r['supplying_is_ancestor_of_integrator']:errors.append('Supplying source not integrated')
 if a not in moving and not r['supplying_is_ancestor_of_remote']:errors.append('Published remote does not descend from actual supplying source')
 if r['remote_receipt']['errors']:errors.append('Remote receipt invalid')
 if r['supplying_receipt']['errors']:errors.append('Supplying receipt invalid')
 if a in known_mixed:
  sha,count=known_mixed[a];r['known_mixed_remote_preserved']=remote==sha and len(r['remote_ownership_errors'])==count
  if not r['known_mixed_remote_preserved']:errors.append('Known mixed remote identity/scope changed')
  r['publication_status']='MIXED_REMOTE_PRESERVED' if r['known_mixed_remote_preserved'] else 'UNEXPECTED_PUBLICATION_CHANGE'
 elif a in moving:r['publication_status']='SOURCE_MOVING'
 elif r['remote_ownership_errors']:
  r['publication_status']='SCOPE_FAIL';errors.append('Remote has foreign paths')
 else:r['publication_status']='PUBLISHED_OWNED' if not errors else 'VALIDATION_FAIL'
 r['errors']=errors;return r
selected=[r for r in rows if 81<=int(r['agent'])<=100]
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(validate,selected))
proofpath=out/'preserving-publications.json';proof=json.loads(proofpath.read_text());proofresults=[]
proofrows=[r for r in rows if r['agent'] in {'004','057'}]
proofremote=command('ls-remote','--heads','origin',*['refs/heads/'+r['branch'] for r in proofrows]).stdout;proofremotes={ref.removeprefix('refs/heads/'):sha for sha,ref in (line.split() for line in proofremote.splitlines())}
for a in ('004','057'):
 row=next(r for r in rows if r['agent']==a);p=next(p for p in proof['publications'] if p['agent']==a);pure=p['accepted_pure_source_sha'];published=p['published_remote_sha'];previous=p['previous_remote_sha'];changes=command('diff','--no-renames','--name-only',previous,published).stdout.splitlines()
 check={'agent':a,'accepted_pure_source_sha':pure,'published_remote_sha':published,'observed_remote_sha':proofremotes[row['branch']],'remote_matches_proof':proofremotes[row['branch']]==published,'preserved_previous_remote_ancestor':ancestor(previous,published),'preserved_pure_source_ancestor':ancestor(pure,published),'pure_source_ownership_errors':ns['ownership'](root,rows,a,base,pure),'publication_delta_foreign_paths':[name for name in changes if not ns['owned'](row,name)],'source_proof':{'path':str(proofpath),'sha256':digest(proofpath)}}
 check['status']='PASS' if check['remote_matches_proof'] and check['preserved_previous_remote_ancestor'] and check['preserved_pure_source_ancestor'] and not check['pure_source_ownership_errors'] and not check['publication_delta_foreign_paths'] else 'FAIL';proofresults.append(check)
unexpected=[{'agent':r['agent'],'errors':r.get('errors',[]),'status':r['publication_status']} for r in results if r.get('errors') and r['agent'] not in moving]
report={'schema':2,'mode':'READ_ONLY_PUBLICATION_PREFLIGHT','status':'PREFLIGHT_COMPLETE_SOURCE_MOVING' if not unexpected and all(p['status']=='PASS' for p in proofresults) else 'PREFLIGHT_HAS_UNEXPECTED_FAILURES','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'validator_source_sha':source,'validator_sha256':hashlib.sha256(code.encode()).hexdigest(),'plan_sha256':hashlib.sha256(plan.encode()).hexdigest(),'campaign_base':base,'roles_observed':len(results),'source_moving_agents':sorted(moving),'known_mixed_remotes_preserved':sorted(known_mixed),'remote_snapshot':{'path':str(remote_path),'sha256':digest(remote_path),'query':'git ls-remote --heads origin exact20branchrefs','observed_at':datetime.datetime.fromtimestamp(remote_path.stat().st_mtime,datetime.timezone.utc).isoformat()},'preserving_publications':proofresults,'roles':results,'unexpected_failures':unexpected,'limitations':['Remote snapshot is an observation, not a final freeze or claim of passing runtime/physical/release gates.','088/098/099/100 source/metadata remain moving; recorded errors there remain visible and require final revalidation.','Mixed085/094 remote trees are preserved unchanged and not accepted as owner-only deliveries. Actual pure supplying commits are checked separately.','No source files, Git refs, index, history, AVD or device state modified; no fetch/reset/force or ADB performed.']}
p=out/'branch-publication-preflight-v2.json';assert not p.exists();p.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n');print(json.dumps({'report':str(p),'sha256':digest(p),'status':report['status'],'counts':{key:sum(r['publication_status']==key for r in results) for key in sorted({r['publication_status'] for r in results})},'unexpected_failures':unexpected,'moving_errors':[{'agent':r['agent'],'errors':r.get('errors')} for r in results if r['agent'] in moving]}))
