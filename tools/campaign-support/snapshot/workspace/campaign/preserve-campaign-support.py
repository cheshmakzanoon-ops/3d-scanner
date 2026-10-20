#!/usr/bin/env python3
"""Preserve authored environment tooling and text evidence after a user stop."""
import hashlib,json,os,re,shutil,tarfile
from pathlib import Path,PurePosixPath
base=Path('/workspace/campaign-support')
out=base/'tools/campaign-support/snapshot'
suffixes={'.py','.sh','.kt','.kts','.cpp','.hpp','.h','.c','.md','.json','.toml','.yaml','.yml','.gradle','.txt','.log','.xml','.sha256','.sha','.exit','.patch','.diff','.csv','.comp','.glsl'}
skip={'.git','.gradle','.kotlin','.cxx','node_modules','__pycache__','.pytest_cache','.aws','.codex','.agents','native-configuration','validation-configuration','junit-results','generated-untracked-after-build'}
patterns=[('private_key',re.compile(rb'-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----')),('github_token',re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})')),('openai_token',re.compile(rb'sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}')),('aws_access_key',re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'))]
rows=[]
def preserve(source,dest,data=None):
 content=source.read_bytes() if data is None else data
 for kind,pattern in patterns:
  if pattern.search(content):raise SystemExit('Credential marker requires review: '+kind+' in '+str(source))
 target=out/dest
 target.parent.mkdir(parents=True,exist_ok=True)
 target.write_bytes(content)
 if data is None and os.access(source,os.X_OK):target.chmod(0o755)
 rows.append({'original_path':str(source),'saved_path':str(target.relative_to(base)),'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()})
for root in map(Path,('/workspace/cloud-setup','/workspace/campaign','/workspace/agents','/tmp')):
 for d,dirs,files in os.walk(root):
  p=Path(d)
  if p!=root and ((p/'.git').is_file() or (p/'.git').is_dir()):dirs[:]=[];continue
  dirs[:]=[n for n in dirs if n not in skip and not n.startswith('build') and n!='android-agent']
  for n in sorted(files):
   f=p/n
   if f.is_symlink() or not f.is_file() or f.suffix not in suffixes:continue
   if root==Path('/tmp') and not any(x.startswith(('rf','agent','independent_campaign')) for x in f.relative_to(root).parts):continue
   preserve(f,Path(str(f).lstrip('/')))
# Preserve handmade probes in ignored build areas; omit CMake/KSP generated code.
extra=json.loads(Path('/workspace/campaign/build-area-source-candidates.json').read_text())
for item in extra:
 f=Path(item['path'])
 if f.name in {'cmake_install.cmake','CTestTestfile.cmake'} or 'kspCaches' in f.parts:continue
 preserve(f,Path(str(f).lstrip('/')))
# Older authored probes can exist only in this agent's preservation tarball.
archive=Path('/workspace/campaign/receipt-drafts/046-050/stop-support.tar.gz')
archive_members=[]
with tarfile.open(archive) as t:
 for m in t.getmembers():
  if not m.isfile():continue
  name=PurePosixPath(m.name)
  assert not name.is_absolute() and '..' not in name.parts
  data=t.extractfile(m).read()
  saved=Path('archived-046-050')/str(name)
  if Path(m.name).suffix in suffixes:preserve(Path('tar:'+m.name),saved,data)
  archive_members.append({'member':m.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'text_saved':Path(m.name).suffix in suffixes})
(out/'046-050-archive-members.json').write_text(json.dumps(archive_members,indent=2)+'\n')
# Ensure every explicitly reported outside-checkout source/draft was captured.
reported=json.loads(Path('/workspace/agents/031-035-stop-save-report.json').read_text())['outside_checkout_sources_for_root_archive']
by_path={r['original_path']:r for r in rows}
for r in reported:assert by_path[r['path']]['sha256']==r['sha256'],r['path']
(base/'docs/campaign-stop').mkdir(parents=True,exist_ok=True)
shutil.copyfile('/workspace/campaign/DELIVERY.md',base/'docs/campaign-stop/DELIVERY.md')
(base/'docs/campaign-stop/PRESERVATION_INVENTORY.json').write_text(json.dumps({'purpose':'User-requested save-only stop; no application development or test execution','files':rows,'excluded':'Third-party toolchains/dependency caches, credentials, generated binaries/APKs/native libraries and reference-photo data; existing hashes/receipts preserved'},indent=2)+'\n')
(base/'docs/campaign-stop/README.md').write_text('''# User-requested development stop\n\nAll authored source and unfinished implementation is committed on its owning agent branch. This support branch preserves the setup scripts, external validation/diagnostic tools, draft handoffs and text evidence that previously lived outside Git.\n\nSee [the complete branch/commit delivery index](DELIVERY.md) and [the file inventory with original paths and SHA-256 values](PRESERVATION_INVENTORY.json). Archived helper scripts are historical operational tools, not production application entry points. Existing validation results retain their exact source identities; saved WIP is not declared qualified.\n\nDevelopment and test execution were stopped. No force push or main merge was performed. Generated SDK/dependency caches and APK/executable/library/archive binaries remain local. Their available checksums and text receipts are preserved; public reference photos and authentication stores are excluded.\n''')
print('Preserved',len(rows),'text/source/evidence files;',sum(r['bytes'] for r in rows),'bytes')
