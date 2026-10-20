#!/usr/bin/env python3
"""Local-only integration of explicitly reviewed immutable campaign commits."""
import pathlib, subprocess, sys
root=pathlib.Path(sys.argv[2]) if len(sys.argv)==3 else pathlib.Path('/workspace/campaign/integration')
def run(*args,check=True):
    return subprocess.run(['git','-C',str(root),*args],check=check,text=True,capture_output=True)
if len(sys.argv) not in (2,3):
    raise SystemExit('Pass one reviewed full commit SHA and optional isolated checkout root')
sha=sys.argv[1]
if len(sha)!=40 or any(c not in '0123456789abcdef' for c in sha):
    raise SystemExit('Full immutable SHA required')
if run('status','--porcelain').stdout:
    raise SystemExit('Integration checkout has changes; inspect and resolve first')
if run('merge-base','--is-ancestor',sha,'HEAD',check=False).returncode == 0:
    print('Reviewed commit already included: '+sha)
    raise SystemExit(0)
r=run('merge','--no-ff','--no-commit',sha,check=False)
if r.returncode:
    conflicts=run('diff','--name-only','--diff-filter=U').stdout.splitlines()
    if conflicts!=['SOURCE_MANIFEST.sha256']:
        print(r.stdout+r.stderr)
        raise SystemExit('Semantic conflict requires review: '+repr(conflicts))
    run('add','SOURCE_MANIFEST.sha256')
subprocess.run([sys.executable,'/workspace/cloud-setup/regenerate-agent-manifest.py'],cwd=root,check=True)
run('add','SOURCE_MANIFEST.sha256')
run('diff','--check','--cached')
print(run('commit','-m',f'test: locally integrate reviewed campaign commit {sha}').stdout)
print(run('rev-parse','HEAD').stdout.strip())
