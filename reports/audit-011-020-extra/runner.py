from pathlib import Path
import hashlib,json,re,subprocess,sys
repo=Path('/workspace/scratch/16a420565b0d/3d-scanner')
base=repo/'app/src/main/java/com/realityforge/scanner'
testbase=repo/'app/src/test/java/com/realityforge/scanner/agents'
toolchain=Path('/dev/shm/scanner-jvm-checks')
deps=json.loads((toolchain/'dependencies.json').read_text())
for dep in deps:
 jar=toolchain/dep['file']
 with jar.open('rb') as handle: digest=hashlib.file_digest(handle,'sha256').hexdigest()
 assert jar.stat().st_size==dep['bytes'] and digest==dep['sha256'], f"Dependency mismatch: {dep['file']}"
out=Path(__file__).parent
n=1
while (out/f'run-{n:02d}').exists(): n+=1
output=out/f'run-{n:02d}'
output.mkdir()
(output/'runner.py').write_bytes(Path(__file__).read_bytes())
(output/'tmp').mkdir()
sources=[]
folders=['capture/analysis','capture/controls','capture/guidance','capture/metadata','capture/permissions','capture/session','data/importing','data/curation']
for folder in folders:
 for p in sorted((base/folder).glob('*.kt')):
  imports=re.findall(r'^import ([^\n]+)',p.read_text(),re.M)
  if not any(i.startswith(('android.','androidx.','com.google.','org.opencv.')) for i in imports): sources.append(p)
sources.append(base/'imaging/ImageContracts.kt')
sources += [base/f'data/cachegc/{name}.kt' for name in ['CacheCollector','CacheGcContracts','CacheInventory','CacheRetentionPlanner','CacheReuseValidator']]
tests=[]
for i in range(11,19): tests += sorted((testbase/f'a{i:03d}').glob('*.kt'))
tests += [p for p in sorted((testbase/'a020').glob('*.kt')) if p.name not in {'GlobalCacheInventoryServiceTest.kt','CacheDescriptorBindingsTest.kt'}]
sources=[p for p in sources if p.name != 'SavedPhotoGuidanceSession.kt']
tests=[p for p in tests if p.name != 'SavedPhotoGuidanceSessionTest.kt']
files=sources+tests
compiler_cp=':'.join(str(p) for p in sorted(toolchain.glob('*.jar')) if p.name!='kotlinx-coroutines-core-jvm-1.10.2.jar')
runtime_cp=':'.join(str(toolchain/n) for n in ['kotlin-stdlib-2.1.21.jar','kotlinx-coroutines-core-jvm-1.10.2.jar','json-20250517.jar','junit-4.13.2.jar','hamcrest-core-1.3.jar','annotations-13.0.jar'])
classes=output/'classes'
command=['java','-Xmx512m',f'-Djava.io.tmpdir={output}/tmp','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',runtime_cp,'-d',str(classes)]+[str(p) for p in files]
test_classes=['.'.join(str(p.relative_to(repo/'app/src/test/java')).removesuffix('.kt').split('/')) for p in tests if p.name.endswith('Test.kt')]
provenance={'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'scope':'Existing portable 011-018 and pure 020 JVM tests; actual sources and pinned dependencies, no Android or Room substitutions','toolchain':deps,'dependency_hashes_verified':True,'excluded_android_scope':['agents/a017/SavedPhotoGuidanceSessionTest.kt','agents/a019/*','agents/a020/GlobalCacheInventoryServiceTest.kt','agents/a020/CacheDescriptorBindingsTest.kt'],'files':[{'path':str(p.relative_to(repo)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files],'compiler_command':command,'runtime_classpath':runtime_cp,'test_classes':test_classes}
(output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print('Output:',output,'production source files:',len(sources),'test classes:',len(test_classes),flush=True)
with (output/'compile.log').open('w') as log: result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
print('Kotlin compile exit:',result.returncode,flush=True)
print((output/'compile.log').read_text()[-16000:],flush=True)
if result.returncode: sys.exit(result.returncode)
with (output/'junit.log').open('w') as log: result=subprocess.run(['java','-Xmx512m',f'-Djava.io.tmpdir={output}/tmp','-cp',str(classes)+':'+runtime_cp,'org.junit.runner.JUnitCore']+test_classes,stdout=log,stderr=subprocess.STDOUT,timeout=180)
print((output/'junit.log').read_text()[-16000:],flush=True)
provenance['junit_exit_code']=result.returncode
provenance['source_files_unchanged']=all(hashlib.sha256((repo/f['path']).read_bytes()).hexdigest()==f['sha256'] for f in provenance['files'])
(output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
assert provenance['source_files_unchanged']
sys.exit(result.returncode)
