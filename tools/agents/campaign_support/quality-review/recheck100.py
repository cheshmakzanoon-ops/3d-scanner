from pathlib import Path
import json,subprocess,sys,tempfile,shlex
ROOT=Path('/workspace/agent-workspaces/100');sys.path.insert(0,'/tmp/quality-review/source-4b/tools/agents')
import campaign,integrate

def g(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True,stderr=subprocess.DEVNULL).strip()
def write(p,name,text):q=p/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(text)
def commit(p,message):g(p,'add','.');g(p,'commit','-qm',message);return g(p,'rev-parse','HEAD')
def repo(prefix):
 p=Path(tempfile.mkdtemp(prefix=prefix,dir='/tmp/quality-review'));g(p,'init','-q');g(p,'config','user.name','Review fixture');g(p,'config','user.email','review@example.invalid');return p

def fixture():
 p=repo('fixed100-');write(p,campaign.PLAN,(ROOT/campaign.PLAN).read_text());write(p,'tools/source_manifest.py',(ROOT/'tools/source_manifest.py').read_text());write(p,'SOURCE_MANIFEST.sha256','');write(p,'docs/agents/integration/deliveries.json',json.dumps({'schema':1,'deliveries':[]}));write(p,'tools/device/offline_privacy/example.txt','base');base=commit(p,'base')
 g(p,'switch','-qc','agents/089-offline-privacy');write(p,'tools/device/offline_privacy/example.txt','candidate');source=commit(p,'candidate owned089')
 g(p,'switch','-qc','agents/100-integration-lead',base);write(p,'docs/agents/integration/campaign-base.json',json.dumps({'campaign_base':base}));foundation=commit(p,'freeze true base')
 return p,base,source,foundation

def merge(p,base,source,checks=[]):
 old_root,old_argv=integrate.ROOT,sys.argv;integrate.ROOT=p
 sys.argv=['integrate','--agent','089','--source-sha',source,'--base',base]
 for check in checks:sys.argv += ['--check',check]
 try:
  try:return integrate.main()
  except SystemExit as e:return e.code
 finally:integrate.ROOT=old_root;sys.argv=old_argv

p,base,source,foundation=fixture()
fix='from pathlib import Path; Path("tools/device/offline_privacy/example.txt").write_text("fixed and tested")'
check='from pathlib import Path; assert Path("tools/device/offline_privacy/example.txt").read_text()=="fixed and tested"'
code=merge(p,base,source,[f'{sys.executable} -c {shlex.quote(fix)}',f'{sys.executable} -c {shlex.quote(check)}'])
assert code!=0 and g(p,'rev-parse','HEAD')==foundation
print('PASS_POSTCHECK_WORKTREE_DRIFT_REJECTED',json.dumps({'code':code,'HEAD_unchanged':True,'root':str(p)}))

p,base,source,foundation=fixture()
assert merge(p,source,source)==2 and g(p,'rev-parse','HEAD')==foundation
print('PASS_WRONG_FROZEN_BASE_REJECTED')
orphan=g(p,'commit-tree',g(p,'rev-parse',source+'^{tree}'),'-m','unrelated candidate')
assert merge(p,base,orphan)==2 and g(p,'rev-parse','HEAD')==foundation
print('PASS_NON_DESCENDANT_DELIVERY_REJECTED')
assert merge(p,base,source,[f'{sys.executable} -c {shlex.quote("from pathlib import Path; assert Path(\"tools/device/offline_privacy/example.txt\").read_text()==\"candidate\"")}'])==0
assert g(p,'show','HEAD:tools/device/offline_privacy/example.txt')=='candidate'
assert not g(p,'status','--porcelain')
print('PASS_CLEAN_REVIEWED_DELIVERY_COMMITS')

p=repo('fixed-receipt-');write(p,'seed','base');base=commit(p,'base')
write(p,'docs/agents/status/089.md',json.dumps({'agent':'089','state':'DONE','implementation_commits':[base],'remaining_work':[],'external_blockers':[],'acceptance_results':[{'result':'FAIL','criterion':'required privacy gate','command':'actual-test-command','source_sha':base}]}));source=commit(p,'contradictory receipt')
g(p,'update-ref','refs/remotes/origin/agents/089-offline-privacy',source)
row={'agent':'089','branch':'agents/089-offline-privacy','receipt':'docs/agents/status/089.md','existing':[],'reserved':[]}
r=campaign.index(p,[row],base);assert not r['campaign_complete'] and r['deliveries'][0]['state']=='INVALID_RECEIPT'
print('PASS_CONTRADICTORY_DONE_REJECTED',json.dumps(r['deliveries'][0]['receipt_errors']))

p=repo('fixed-ledger-');write(p,'other.txt','base');write(p,'root.txt','base');base=commit(p,'base')
write(p,'other.txt','unreviewed edit');write(p,'root.txt','integration file also changed');bad=commit(p,'mixed change')
write(p,'docs/agents/integration/deliveries.json',json.dumps({'deliveries':[{'agent':'089','source_sha':bad}]}));revision=commit(p,'self-attested ledger')
rows=[{'agent':'089','receipt':'docs/agents/status/089.md','existing':['other.txt'],'reserved':[]},{'agent':'100','receipt':'docs/agents/status/100.md','existing':['root.txt'],'reserved':['docs/agents/integration/']}]
assert campaign.ownership(p,rows,'100',base,revision)==['other.txt']
print('PASS_MIXED_SOURCE_LEDGER_REJECTED')

# Remaining binding issue: a syntactically complete receipt names a real older
# tested commit, then runtime source changes. Index still reports complete.
p=repo('stale-done-');write(p,'owned.py','print("tested old source")');base=commit(p,'tested implementation')
record={'agent':'089','state':'DONE','implementation_commits':[base],'remaining_work':[],'external_blockers':[],
 'acceptance_results':[{'criterion':'required gate','result':'PASS','command':'python owned.py','source_sha':base}]}
write(p,'owned.py','raise RuntimeError("new untested runtime defect")');write(p,'docs/agents/status/089.md',json.dumps(record));source=commit(p,'untested runtime change retaining old receipt')
g(p,'update-ref','refs/remotes/origin/agents/089-offline-privacy',source)
row={'agent':'089','branch':'agents/089-offline-privacy','receipt':'docs/agents/status/089.md','existing':['owned.py'],'reserved':[]}
r=campaign.index(p,[row],base)
print('REMAINING_STALE_DONE_SOURCE',json.dumps({'complete':r['campaign_complete'],'receipt_errors':r['deliveries'][0]['receipt_errors'],'tested_source':base,'untested_source':source,'root':str(p)}))
assert r['campaign_complete']

# A non-conflict merge failure (untracked local path blocks new source) returns1.
# The helper currently treats anyreturn1 as a manifest conflict and commitsledger.
p,base,source,foundation=fixture()
g(p,'switch','agents/089-offline-privacy');write(p,'tools/device/offline_privacy/newfile.py','delivered implementation');source=commit(p,'add owned source')
g(p,'switch','agents/100-integration-lead');write(p,'tools/device/offline_privacy/newfile.py','untracked local user file')
code=merge(p,base,source)
ancestor=subprocess.run(['git','-C',str(p),'merge-base','--is-ancestor',source,'HEAD']).returncode
print('PASS_NONCONFLICT_MERGE_FAILURE_REJECTED',json.dumps({'code':code,'source_is_ancestor':ancestor==0,'source_tracked_in_HEAD':bool(g(p,'ls-tree','--name-only','HEAD','tools/device/offline_privacy/newfile.py')),'local_untracked_preserved':(p/'tools/device/offline_privacy/newfile.py').read_text(),'root':str(p)}))

# Checks can stage an auto-fix and regenerate the manifest after an earlier check.
# The postcheck equality guards don't freeze the original staged tree itself.
p,base,source,foundation=fixture()
check='from pathlib import Path; assert Path("tools/device/offline_privacy/example.txt").read_text()=="candidate"'
stage='from pathlib import Path; import subprocess,sys; Path("tools/device/offline_privacy/example.txt").write_text("changed after tests"); subprocess.run(["git","add","tools/device/offline_privacy/example.txt"],check=True); subprocess.run([sys.executable,"tools/source_manifest.py","generate","--source","index"],check=True); subprocess.run(["git","add","SOURCE_MANIFEST.sha256"],check=True)'
code=merge(p,base,source,[f'{sys.executable} -c {shlex.quote(check)}',f'{sys.executable} -c {shlex.quote(stage)}'])
print('REMAINING_STAGED_POSTCHECK_MUTATION',json.dumps({'code':code,'committed':g(p,'show','HEAD:tools/device/offline_privacy/example.txt'),'tested_value':'candidate','status':g(p,'status','--porcelain'),'root':str(p)}))
