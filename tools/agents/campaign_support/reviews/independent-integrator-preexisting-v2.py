from pathlib import Path
import subprocess,tempfile,json,shutil,sys,shlex,os
SOURCE=Path('/workspace/agent-workspaces/100');OUT=Path('/workspace/campaign/evidence/100')
with tempfile.TemporaryDirectory(prefix='review100-untracked-',dir=OUT) as folder:
 root=Path(folder)
 def git(*args):return subprocess.check_output(['git',*args],cwd=root,text=True).strip()
 git('init','-q');git('config','user.email','fixture@example.invalid');git('config','user.name','Synthetic independent fixture')
 for name in ['.gitignore','tools/agents/campaign.py','tools/agents/integrate.py','tools/source_manifest.py','PRODUCTION_100_AGENT_EXECUTION_PLAN.md']:
  target=root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(SOURCE/name,target)
 def commit(message):
  git('add','.')
  subprocess.run([sys.executable,'tools/source_manifest.py','generate','--source','index'],cwd=root,check=True,capture_output=True)
  git('add','SOURCE_MANIFEST.sha256');git('commit','-qm',message);return git('rev-parse','HEAD')
 base=commit('synthetic campaign base')
 git('switch','-qc','agents/089-runtime-offline-privacy')
 consumer=root/'tools/device/offline_privacy/consumer.py';consumer.parent.mkdir(parents=True);consumer.write_text('from extra import value\nassert value == 42\nprint("Synthetic candidate imported untracked source")\n')
 delivery=commit('scope-owned source delivery')
 git('switch','-qc','agents/100-integration-lead',base)
 anchor=root/'docs/agents/integration/campaign-base.json';anchor.parent.mkdir(parents=True);anchor.write_text(json.dumps({'campaign_base':base}));original=commit('freeze synthetic base')
 extra=root/'tools/device/offline_privacy/extra.py';extra.parent.mkdir(parents=True,exist_ok=True);extra.write_text('value = 42\n')
 code="import subprocess,sys; subprocess.run([sys.executable,'tools/device/offline_privacy/consumer.py'],check=True)"
 command=sys.executable+' -c '+shlex.quote(code)
 env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1')
 result=subprocess.run([sys.executable,'tools/agents/integrate.py','--agent','089','--source-sha',delivery,'--base',base,'--check',command],cwd=root,capture_output=True,text=True,env=env)
 after=git('rev-parse','HEAD');untracked=git('ls-files','--others','--exclude-standard').splitlines();tree=git('ls-tree','-r','--name-only','HEAD').splitlines()
 record={'schema':1,'reviewed_tool_root_source_sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=SOURCE,text=True).strip(),'case':'preexisting untracked source imported by candidate check','integrator_exit_code':result.returncode,'committed_merge':after!=original,'untracked_files_after_success':untracked,'untracked_module_present_in_committed_tree':'tools/device/offline_privacy/extra.py' in tree,'stdout':result.stdout,'stderr':result.stderr,'expected':'Reject source-only verification inputs that are absent from the reviewed index, or verify in a clean isolated checkout.'}
 path=OUT/'independent-integrator-preexisting-v2.json';path.write_text(json.dumps(record,indent=2,sort_keys=True)+'\n');print(json.dumps({key:record[key] for key in ('case','integrator_exit_code','committed_merge','untracked_files_after_success','untracked_module_present_in_committed_tree')}))
