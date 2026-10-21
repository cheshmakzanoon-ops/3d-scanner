from pathlib import Path
import importlib.util,json,subprocess,sys,tempfile,shutil
ROOT=Path('/workspace/agent-workspaces/100');FUZZ=Path('/workspace/agent-workspaces/082')
sys.path.insert(0,str(ROOT/'tools/agents'))
import campaign,integrate

def g(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True,stderr=subprocess.DEVNULL).strip()
def repo(prefix):
 p=Path(tempfile.mkdtemp(prefix=prefix,dir='/tmp/quality-review'));g(p,'init','-q');g(p,'config','user.name','Review fixture');g(p,'config','user.email','review@example.invalid');return p

def write(p,name,text):q=p/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(text)
def commit(p,message):g(p,'add','.');g(p,'commit','-qm',message);return g(p,'rev-parse','HEAD')

# Explicit failed receipt can close index because only top-level state is checked.
p=repo('receipt-');write(p,'seed','base');base=commit(p,'base')
write(p,'docs/agents/status/089.md',json.dumps({'agent':'089','state':'DONE','acceptance_results':[{'result':'FAIL','criterion':'required privacy gate'}]}));source=commit(p,'contradictory receipt')
g(p,'update-ref','refs/remotes/origin/agents/089-offline-privacy',source)
row={'agent':'089','branch':'agents/089-offline-privacy','receipt':'docs/agents/status/089.md','existing':[],'reserved':[]}
print('FAIL_RECEIPT_INDEX',json.dumps(campaign.index(p,[row],base)))

#100 ownership laundering: source-owner delta itself violates its ownership.
p=repo('ledger-');write(p,'other.txt','base');write(p,'root.txt','base');base=commit(p,'base')
write(p,'other.txt','unreviewed edit');write(p,'root.txt','integration file also changed');bad=commit(p,'100 unreviewed mixed change')
write(p,'docs/agents/integration/deliveries.json',json.dumps({'deliveries':[{'agent':'089','source_sha':bad}]}));revision=commit(p,'self-attested ledger')
rows=[{'agent':'089','receipt':'docs/agents/status/089.md','existing':['other.txt'],'reserved':[]},{'agent':'100','receipt':'docs/agents/status/100.md','existing':['root.txt'],'reserved':['docs/agents/integration/']}]
print('LEDGER_LAUNDER',json.dumps({'source_owner_errors':campaign.ownership(p,rows,'089',base,bad),'integration_owner_errors':campaign.ownership(p,rows,'100',base,revision),'root':str(p)}))
print('WRONG_BASE_BYPASS',campaign.ownership(p,rows,'089',bad,bad))

# Check modifies then tests unstaged code. Integrate commits unmodified staged code.
p=repo('integrate-');write(p,'PRODUCTION_100_AGENT_EXECUTION_PLAN.md',(ROOT/'PRODUCTION_100_AGENT_EXECUTION_PLAN.md').read_text());write(p,'tools/source_manifest.py',(ROOT/'tools/source_manifest.py').read_text());write(p,'SOURCE_MANIFEST.sha256','');write(p,'docs/agents/integration/deliveries.json',json.dumps({'schema':1,'deliveries':[]}));write(p,'tools/device/offline_privacy/example.txt','base');base=commit(p,'base')
g(p,'switch','-qc','agents/089-offline-privacy');write(p,'tools/device/offline_privacy/example.txt','bad candidate');source=commit(p,'owned candidate')
g(p,'switch','-qc','agents/100-integration-lead',base)
fix='from pathlib import Path; Path("tools/device/offline_privacy/example.txt").write_text("fixed and tested")'
check='from pathlib import Path; assert Path("tools/device/offline_privacy/example.txt").read_text()=="fixed and tested"'
old_root,old_argv=integrate.ROOT,sys.argv
integrate.ROOT=p;sys.argv=['integrate','--agent','089','--source-sha',source,'--base',base,'--check',f'{sys.executable} -c {__import__("shlex").quote(fix)}','--check',f'{sys.executable} -c {__import__("shlex").quote(check)}']
try:result=integrate.main()
finally:integrate.ROOT=old_root;sys.argv=old_argv
print('CHECK_WORKTREE_DRIFT',json.dumps({'returncode':result,'committed':g(p,'show','HEAD:tools/device/offline_privacy/example.txt'),'tested_worktree':(p/'tools/device/offline_privacy/example.txt').read_text(),'status':g(p,'status','--porcelain'),'root':str(p)}))

# Fake uninstrumented executables, zero completed fuzz iterations, still PASS.
spec=importlib.util.spec_from_file_location('fuzz_review',FUZZ/'tools/qa/fuzz/run.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
build=Path(tempfile.mkdtemp(prefix='fake-fuzz-',dir='/tmp/quality-review'))
for kind in module.KINDS:
 q=build/('rf_fuzz_'+kind);q.write_text('#!/bin/sh\necho "Done 0 runs in 0 second(s)"\n');q.chmod(0o755)
out=Path(tempfile.mkdtemp(prefix='fake-fuzz-receipt-',dir='/tmp/quality-review'))
code=module.main(['--output',str(out),'--build',str(build),'--compiler','/workspace/.tools/native-qa/llvm/usr/bin/clang++-19','--skip-build','--runs','1000','--seconds','1'])
evidence=json.loads((out/'evidence.json').read_text())
print('FAKE_FUZZ_PASS',json.dumps({'returncode':code,'status':evidence['status'],'source_sha':evidence['source_sha'],'completed_runs':[s.get('completed_runs') for s in evidence['steps']],'root':str(out)}))
