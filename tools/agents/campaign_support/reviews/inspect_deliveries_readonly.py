import datetime,hashlib,importlib.util,json,os,re,subprocess
from pathlib import Path

BASE=Path('/workspace/campaign/base.txt').read_text().strip()
OUT=Path('/workspace/campaign/evidence/100')
PENDING={'091':'Release/package/split evidence follow-up expected; refresh source and receipts before final report.',
         '093':'CI scope/excluded-specialist integration follow-up expected; do not treat local workflow checks as remote execution.',
         '095':'Dependency/package attribution and final release-audit follow-up expected.',
         '099':'Final integrated candidate/source/artifact qualification receipt expected; current owned candidate is not Agent100 final candidate.'}

def git(root,*args,allow_failure=False):
    result=subprocess.run(['git','-C',str(root),*args],text=True,capture_output=True,env={**os.environ,'GIT_OPTIONAL_LOCKS':'0'})
    if result.returncode and not allow_failure:raise RuntimeError(result.stderr)
    return result.stdout.strip()

def scalar(s):
    s=s.strip()
    if not s:return None
    try:return json.loads(s)
    except (ValueError,TypeError):pass
    if s.startswith('[') and s.endswith(']'):return [scalar(x) for x in s[1:-1].split(',') if x.strip()]
    if len(s)>=2 and s[0]==s[-1]=="'":return s[1:-1].replace("''", "'")
    if s in ('null','~'):return None
    return s

def parse(text):
    decoder=json.JSONDecoder()
    for match in re.finditer(r'\{',text):
        try:
            obj,_=decoder.raw_decode(text[match.start():])
            if isinstance(obj,dict) and 'agent' in obj and 'state' in obj:return obj,'json'
        except ValueError:pass
    data={};active=None;record=None;in_block=False
    for line in text.splitlines():
        if line.startswith(('```','---')):continue
        if line.startswith('#') and in_block:break
        top=re.match(r'^([a-z_]+):\s*(.*)$',line)
        if top:
            in_block=True;active=top[1];value=top[2]
            data[active]=scalar(value) if value else []
            record=None
            continue
        if active is None:continue
        item=re.match(r'^  - (.*)$',line)
        if item:
            if not isinstance(data[active],list):continue
            field=re.match(r'^([a-z_]+):\s*(.*)$',item[1])
            if field:record={field[1]:scalar(field[2])};data[active].append(record)
            else:record=None;data[active].append(scalar(item[1]))
            continue
        field=re.match(r'^    ([a-z_]+):\s*(.*)$',line)
        if field and record is not None:record[field[1]]=scalar(field[2])
    if not data:raise ValueError('No recognized receipt record')
    return data,'compact-yaml'

def listify(value):
    if value is None:return []
    return value if isinstance(value,list) else [value]

spec=importlib.util.spec_from_file_location('campaign_readonly','/workspace/agent-workspaces/100/tools/agents/campaign.py')
checker=importlib.util.module_from_spec(spec);spec.loader.exec_module(checker)
rows=[]
for number in range(81,100):
    role=f'{number:03}';root=Path('/workspace/agent-workspaces')/role
    observed=datetime.datetime.now(datetime.timezone.utc).isoformat()
    head=git(root,'rev-parse','HEAD');branch=git(root,'branch','--show-current')
    receipt_path=f'docs/agents/status/{role}.md';receipt=git(root,'show',f'{head}:{receipt_path}')
    data,encoding=parse(receipt)
    contracts=git(root,'ls-tree','-r','--name-only',head,'docs/agents/contracts').splitlines()
    requests=git(root,'ls-tree','-r','--name-only',head,'docs/agents/requests').splitlines()
    owned_contracts=[p for p in contracts if Path(p).stem==role]
    owned_requests=[p for p in requests if Path(p).name.startswith(role+'-to-')]
    upstream=git(root,'rev-parse','--verify','@{upstream}',allow_failure=True) or None
    changes=git(root,'diff','--name-only','HEAD').splitlines()
    results=listify(data.get('acceptance_results'))
    row={'agent':role,'role':data.get('role'),'branch':branch,'full_sha':head,'observed_at_utc':observed,
         'declared_state':data.get('state'),'campaign_index_state':checker.receipt_state(receipt),
         'receipt_validation_errors':checker.receipt_checks(receipt),'receipt_encoding':encoding,
         'receipt_path':receipt_path,'receipt_blob_sha':git(root,'rev-parse',f'{head}:{receipt_path}'),
         'receipt_sha256':hashlib.sha256(receipt.encode()).hexdigest(),
         'campaign_base':data.get('campaign_base'),'implementation_commits':listify(data.get('implementation_commits')),
         'upstream_tracking_sha_observed':upstream,'head_matches_local_upstream':head==upstream,
         'remote_live_sha_verified_in_this_readonly_snapshot':False,
         'tracked_uncommitted_paths_observed':changes,
         'expected_pending_followup':role in PENDING,'pending_followup_note':PENDING.get(role),
         'owned_paths_changed_from_receipt':listify(data.get('owned_paths_changed')),
         'actual_changed_paths_since_campaign_base':git(root,'diff','--name-only',BASE,head).splitlines(),
         'owned_handoff_paths':sorted(set(owned_contracts+owned_requests)),
         'interfaces_from_receipt':listify(data.get('interfaces_published')),
         'requests_from_receipt':listify(data.get('integration_requests')),
         'acceptance_results':results,
         'failed_acceptance_gates':[r for r in results if isinstance(r,dict) and r.get('result')=='FAIL'],
         'unrun_or_blocked_acceptance_gates':[r for r in results if isinstance(r,dict) and r.get('result') in ('NOT_RUN','BLOCKED_EXTERNAL')],
         'remaining_work':listify(data.get('remaining_work')),'external_blockers':listify(data.get('external_blockers')),
         'next_action':data.get('next_action'),'receipt_extra_fields':{k:v for k,v in data.items() if k not in {'agent','role','branch','campaign_base','implementation_commits','state','owned_paths_changed','interfaces_published','integration_requests','acceptance_results','remaining_work','external_blockers','next_action'}},
         'narrative':receipt[receipt.find('\n# ')+1:] if encoding!='json' and '\n# ' in receipt else receipt,
         'head_changed_during_observation':git(root,'rev-parse','HEAD')!=head}
    rows.append(row)
report={'schema':'realityforge.delivery-summary-draft.v1','read_only_snapshot':True,'observed_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'campaign_base':BASE,'scope':'Agents081–099 own worktree HEAD and committed status receipts; no build, device, push, fetch or commit executed.',
        'provisional':True,'refresh_before_final_report':['091','093','095','099'],
        'evidence_interpretation':'Reported tests/receipts are not rerun in this snapshot. Synthetic/parser/build/alignment successes never establish physical/full-release acceptance. Per-role counts overlap and must not be summed as unique tests.',
        'deliveries':rows}
(OUT/'delivery-summary-draft.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
for r in rows:
    print(json.dumps({key:r[key] for key in ('agent','full_sha','declared_state','campaign_index_state','receipt_validation_errors','expected_pending_followup','tracked_uncommitted_paths_observed')},ensure_ascii=False))
