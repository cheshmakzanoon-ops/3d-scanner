from pathlib import Path
import subprocess,sys,json,yaml
role=sys.argv[1]
assert role in [f'{n:03}' for n in range(61,81) if n not in [67,77,80]]
root=Path('/workspace/agent-work')/role; allowed={f'docs/agents/status/{role}.md','SOURCE_MANIFEST.sha256'}
def git(*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def run(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
old=git('rev-parse','HEAD'); branch=git('branch','--show-current'); receipt=yaml.safe_load((root/f'docs/agents/status/{role}.md').read_text())
assert receipt['agent']==role and receipt['state']!='DONE'
assert set(git('diff','--name-only').splitlines()).issubset(allowed)
assert not git('diff','--cached','--name-only')
git('add',f'docs/agents/status/{role}.md')
run([sys.executable,'/workspace/campaign-061-080/dependencies/094/source_manifest.py','generate','--source','index','--repo',str(root)])
git('add','SOURCE_MANIFEST.sha256')
manifest=run([sys.executable,'/workspace/campaign-061-080/dependencies/094/source_manifest.py','verify','--source','index','--repo',str(root)])
assert set(git('diff','--cached','--name-only').splitlines())==allowed
git('diff','--cached','--check')
git('commit','-m',f'docs(agent-{role}): record pinned combined validation and open gates')
head=git('rev-parse','HEAD')
assert set(git('diff','--name-only',old,head).splitlines())==allowed
git('push','origin',f'HEAD:refs/heads/{branch}')
remote=git('ls-remote','--heads','origin',f'refs/heads/{branch}').split()[0]
assert remote==head
result={'agent':role,'branch':branch,'previous_head':old,'head':head,'remote_head':remote,'metadata_only':True,'changed_paths':sorted(allowed),'manifest_verifier':json.loads(manifest)}
out=Path('/workspace/campaign-061-080/c16-delivery');out.mkdir(exist_ok=True);(out/f'{role}.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result),flush=True)
