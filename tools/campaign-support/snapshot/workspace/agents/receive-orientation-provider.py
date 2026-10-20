from pathlib import Path
import json
import subprocess
import sys

number, expected, provider, provider_source = sys.argv[1:]
root = Path('/workspace/agents')/number

def git(*args, check=True):
    return subprocess.run(['git', '-C', str(root), *args], text=True, capture_output=True, check=check)

branch = git('branch', '--show-current').stdout.strip()
assert git('rev-parse', 'HEAD').stdout.strip() == expected
assert not git('status', '--porcelain', '--untracked-files=no').stdout.strip()
remote = git('ls-remote', '--heads', 'origin', branch).stdout.strip().split()[0]
assert remote == expected, (number, 'remote changed', remote, expected)
owned = 'app/src/main/java/com/realityforge/scanner/engine/'+('features' if number=='032' else 'matching')
before = git('ls-tree', '-r', 'HEAD', '--', owned).stdout
result = git('merge', '--no-ff', '--no-commit', provider, check=False)
print(result.stdout, result.stderr)
conflicts = git('diff', '--name-only', '--diff-filter=U').stdout.splitlines()
assert result.returncode in (0,1) and (not conflicts or conflicts == ['SOURCE_MANIFEST.sha256']), conflicts
if conflicts:
    git('checkout', '--ours', '--', 'SOURCE_MANIFEST.sha256')
    git('add', 'SOURCE_MANIFEST.sha256')
subprocess.run(['python','/workspace/agents/regen_manifest.py'],cwd=root,check=True)
git('add','SOURCE_MANIFEST.sha256')
git('diff','--cached','--check')
git('commit','-m',f'merge(agent{number}): receive reviewed decoder orientation evidence fix')
merge_sha = git('rev-parse','HEAD').stdout.strip()
assert git('ls-tree','-r','HEAD','--',owned).stdout == before, 'owned production bytes changed'
status_path=root/f'docs/agents/status/{number}.md'
status=json.loads(status_path.read_text())
status.setdefault('reviewed_upstream_integration',{})['012']='73da7ac2c11f1a4e5fbde8277efdaeb7d5f17897'
if number=='035':
    status['reviewed_upstream_integration']['032']=provider
status['orientation_evidence_dependency_integration']={
 'provider_sha':provider,'provider_source_sha':provider_source,'decoder_provider_sha':'73da7ac2c11f1a4e5fbde8277efdaeb7d5f17897',
 'decoder_source_sha':'72cef99b6339eec9a810773582787d362d47d4a2','normal_merge_sha':merge_sha,
 'policy_identity':'rf-owned-image-v3:srgb-argb8888:exif8-centres:ceil-power2:defaultEdge1600:defaultPeak67108864',
 'details':'Reviewed owner012 fixes actual Android missing-EXIF warning/evidence classification: AndroidX synthesizes undefined0 with unknown offset; physical invalid0 remains malformed. Valid orientation pixels, decoder limits, extractor/matcher-owned bytes and pixel-policy identity are unchanged. Existing final2f18 runtime exposed the failure; corrected raw JPEG/TIFF assertions compiled against actual Android dependencies but actual new APK rerun remains pending. This standalone normal-merge head has no new build/runtime claim.'}
if 'reviewed_dependency' in status:
    status['reviewed_dependency']['decoder_provider']='73da7ac2c11f1a4e5fbde8277efdaeb7d5f17897'
status_path.write_text(json.dumps(status,indent=2)+'\n')
git('add',str(status_path.relative_to(root)))
subprocess.run(['python','/workspace/agents/regen_manifest.py'],cwd=root,check=True)
git('add','SOURCE_MANIFEST.sha256')
git('diff','--cached','--check')
subprocess.run(['python','-m','pytest','tests/test_project_contracts.py::test_source_manifest_matches_every_tracked_artifact','-q'],cwd=root,check=True)
git('commit','-m',f'docs(agent{number}): record reviewed metadata-classification provider')
head = git('rev-parse','HEAD').stdout.strip()
assert git('ls-tree','-r','HEAD','--',owned).stdout == before
assert not git('status','--porcelain','--untracked-files=no').stdout.strip()
print(git('push','origin',branch).stderr)
remote = git('ls-remote','--heads','origin',branch).stdout.strip().split()[0]
assert remote==head,(remote,head)
result={'agent':number,'head':head,'merge_sha':merge_sha,'branch':branch,'reviewed_provider':provider,'owned_production_unchanged':True,'remote_verified':True}
Path(f'/workspace/agents/{number}-orientation-provider-receipt.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
