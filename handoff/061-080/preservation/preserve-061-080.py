#!/usr/bin/env python3
"""Preserve existing campaign tooling/evidence without running development or tests."""
import pathlib,os,shutil,json,hashlib,subprocess,datetime,collections
src=pathlib.Path('/workspace/campaign-061-080')
repo=pathlib.Path('/workspace/061-080-environment-handoff')
dest=repo/'handoff/061-080'
exclude_dirs={'combined','dependencies','npm-cache','pip-cache','trimesh-reader','validator','independent-readers','source','build','.git','__pycache__','node_modules','venv','.venv','gradle-quick','caches','.gradle','.cxx','pytest-temp','classes','r-java','bin','lib','lib64','include','share','dist','v4-artifacts'}
text_ext={'.py','.sh','.cpp','.cc','.c','.h','.hpp','.kt','.java','.gradle','.md','.json','.xml','.txt','.tsv','.log','.sha256','.cmake','.yaml','.yml','.csv','.patch','.diff'}
exclude_files={'072-OutlinedTextField-source.kt','Camera-1.56.java'}
entries=[];skipped=collections.Counter()
def copy(p,rel):
 if p.is_symlink():skipped['symlink']+=1;return
 data=p.read_bytes()
 if b'\0' in data: skipped['binary_not_source']+=1;return
 target=dest/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
 entries.append({'path':str(rel),'original_path':str(p),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
for d,ds,fs in os.walk(src):
 ds[:]=[x for x in ds if x not in exclude_dirs and not x.endswith('-classes')]
 for f in fs:
  p=pathlib.Path(d)/f;rel=p.relative_to(src)
  if f in exclude_files:skipped['downloaded_source_reference']+=1;continue
  if p.suffix in text_ext or f in {'CMakeLists.txt','adb','adb-ui-nonstreaming'}:
   copy(p,pathlib.Path('campaign')/rel)
for f in ['activate-3d-scanner.sh','setup-3d-scanner.sh','gradle-cloud.py']:
 copy(pathlib.Path('/workspace/toolchains')/f,pathlib.Path('toolchains')/f)
for p in pathlib.Path('/workspace/toolchains').glob('*.log'):copy(p,pathlib.Path('toolchains')/p.name)
for name in ['update-writer-c16-e290.py','preserve-061-080.py']:
 p=pathlib.Path('/tmp')/name
 if p.exists():copy(p,pathlib.Path('preservation')/name)
# Keep detached validation merge objects as an archival bundle, without publishing
# an integrated product branch or modifying any numbered agent's history.
raw=subprocess.check_output(['git','-C','/workspace/3d-scanner','worktree','list','--porcelain'],text=True)
refs=[];seen=set()
for block in raw.strip().split('\n\n'):
 fields=dict(line.split(' ',1) for line in block.splitlines() if ' ' in line)
 if 'detached' in block and fields['HEAD'] not in seen:
  sha=fields['HEAD'];seen.add(sha);ref='refs/archive/061-080/'+sha[:12]
  subprocess.run(['git','-C','/workspace/3d-scanner','update-ref',ref,sha],check=True)
  refs.append({'ref':ref,'sha':sha,'original_worktree':fields['worktree']})
if refs:
 target=dest/'validation-snapshots.bundle'
 subprocess.run(['git','-C','/workspace/3d-scanner','bundle','create',str(target),*[r['ref'] for r in refs],'^ad3029193f304c70931b94ea7a0df90b69d84b07'],check=True)
 subprocess.run(['git','-C','/workspace/3d-scanner','bundle','verify',str(target)],check=True,stdout=subprocess.DEVNULL)
(dest/'validation-snapshot-refs.json').write_text(json.dumps(refs,indent=2)+'\n')
(dest/'copied-files.json').write_text(json.dumps(entries,indent=2)+'\n')
(dest/'preservation-summary.json').write_text(json.dumps({'preserved_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'files_copied':len(entries),'bytes_copied':sum(e['bytes'] for e in entries),'excluded_categories':['Downloaded SDKs, JDK, Gradle and third-party packages','Build outputs, APKs, libraries, bytecode and generated caches/resources','Disposable pytest temporary repositories and generated test data','Downloaded Material3 and Filament reference sources','Credentials, platform configuration and symlinks'],'skipped_selected_file_counts':dict(skipped),'snapshot_bundle_sha256':hashlib.sha256((dest/'validation-snapshots.bundle').read_bytes()).hexdigest()},indent=2)+'\n')
print(json.dumps({'files':len(entries),'MiB':round(sum(e['bytes'] for e in entries)/1048576,2),'snapshot_refs':len(refs),'bundle_bytes':(dest/'validation-snapshots.bundle').stat().st_size}))
