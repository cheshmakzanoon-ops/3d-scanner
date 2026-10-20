from pathlib import Path
import subprocess,json
root=Path('/workspace/campaign-061-080/combined')
log=Path('/workspace/campaign-061-080/combined-final-merges.log')
def git(*args,check=True):
 r=subprocess.run(['git',*args],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 with log.open('a') as f:f.write('$ git '+' '.join(args)+'\n'+r.stdout+'\n')
 if check and r.returncode:raise RuntimeError(r.stdout)
 return r
roster=json.loads(Path('/workspace/campaign-061-080/roster.json').read_text())
inputs=json.loads(Path('/workspace/campaign-061-080/combined-inputs.json').read_text())
byrole={row['agent']:row for row in inputs}
byrole['004']={'agent':'004','source_sha':'71262455888e051fd03a1df06f276db0db461ef7'}
for role in ['009','088']:
 sha={'009':'0cb6532d921e61a0e56ff0b310cafe6329f6f7cd','088':'0e5d9415c168c46a21899450dcd82c84886095f8'}[role]
 byrole[role]={'agent':role,'source_sha':sha}
for role,data in roster.items():
 sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=data['path'],text=True).strip()
 byrole[role]={'agent':role,'source_sha':sha}
for role,row in sorted(byrole.items()):
 sha=row['source_sha']
 if git('merge-base','--is-ancestor',sha,'HEAD',check=False).returncode==0:continue
 r=git('merge','--no-ff','--no-edit',sha,check=False)
 if r.returncode:
  conflicts=git('diff','--name-only','--diff-filter=U').stdout.splitlines()
  if conflicts!=['SOURCE_MANIFEST.sha256']:raise RuntimeError('Semantic conflict '+str(conflicts))
  git('rm','-f','SOURCE_MANIFEST.sha256')
  subprocess.run(['python','/workspace/campaign-061-080/dependencies/094/source_manifest.py','generate','--source','index','--repo',str(root)],check=True,stdout=subprocess.PIPE)
  git('add','SOURCE_MANIFEST.sha256');git('commit','-m',f'Temporary verification merge: {role} at {sha}')
Path('/workspace/campaign-061-080/combined-final-inputs.json').write_text(json.dumps(list(byrole.values()),indent=2)+'\n')
print(git('rev-parse','HEAD').stdout)
