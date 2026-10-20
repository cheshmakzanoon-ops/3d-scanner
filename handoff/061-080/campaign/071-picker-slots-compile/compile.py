from pathlib import Path
import subprocess,hashlib,json,zipfile,shutil,sys,time
outroot=Path(__file__).resolve().parent
cache=Path('/workspace/toolchains/gradle-user/caches/modules-2/files-2.1')
app=Path('/workspace/campaign-061-080/final-validation/3b9f9157753b/source/app/build')
def jar(group,artifact,version):
 return str(next((cache/group/artifact/version).glob('*/*.jar')))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.21')
coroutines=jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2')
annotations=jar('org.jetbrains','annotations','23.0.0')
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.21'),stdlib,coroutines,annotations,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.21'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330')]
peer=outroot/'peer-classes';peer.mkdir(exist_ok=True)
source_peer={}
for name,src in [('app',app/'tmp/kotlin-classes/debug'),('test',app/'tmp/kotlin-classes/debugAndroidTest')]:
 assert src.is_dir(),src
 for p in sorted(src.rglob('*')):
  if not p.is_file():continue
  rel=p.relative_to(src)
  if str(rel).startswith(('com/realityforge/scanner/ui/processing/','com/realityforge/scanner/agents/a074/')):continue
  rootname=rel.name.split('$')[0].removesuffix('.class')
  if rel.parent.as_posix()=='com/realityforge/scanner/ui' and (rootname=='ScannerAppKt' or rel.name.startswith('ComposableSingletons$ScannerAppKt')):continue
  if rel.parent.as_posix()=='com/realityforge/scanner/ui/navigation' and rootname in ['Destination','AppRoute','PendingKind','PendingRequest','PickerCallbackSlot','PickerCallbackSlots','PickerChannel','PickerConsumption','ExportRestoreSnapshot','PickerCallbackBinding']:continue
  if rel.parent.as_posix()=='com/realityforge/scanner/domain' and rootname in ['OperationId','RequestId','SessionId','JobId','ProjectRevision','OperationKind','OperationContext','BoundExternalRequest','BoundExternalRequestCodec']:continue
  if p.suffix not in ['.class','.kotlin_module']:continue
  dest=peer/name/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest)
  source_peer[f'{name}/{rel}']=sha(dest)
cp=[stdlib,coroutines,annotations,jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3'),str(peer/'app'),str(peer/'test'),'/workspace/toolchains/android-sdk/platforms/android-36/android.jar']
transforms=Path('/workspace/toolchains/gradle-user/caches/8.13/transforms')
cp+=map(str,sorted(p for p in transforms.rglob('*.jar') if 'instrumented' not in p.parts and (p.name=='classes.jar' or p.name.endswith('-runtime.jar'))))
cp+=[str(app/'intermediates/javac/debug/compileDebugJavaWithJavac/classes'),str(outroot/'R.jar')]
cp+=map(str,sorted(p for group in cache.glob('androidx.*') for p in group.rglob('*.jar')))
aars={}
for group in ['androidx.test','androidx.test.ext','androidx.test.services']:
 for aar in sorted((cache/group).rglob('*.aar')):
  with zipfile.ZipFile(aar) as z:
   for entry in z.namelist():
    if entry=='classes.jar' or (entry.startswith('libs/') and entry.endswith('.jar')):
     dest=outroot/'test-aars'/hashlib.sha256(str(aar).encode()).hexdigest()[:12]/entry
     dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(z.read(entry));cp.append(str(dest))
  aars[str(aar)]=sha(aar)
cp=list(dict.fromkeys(cp))
plugin=jar('org.jetbrains.kotlin','kotlin-compose-compiler-plugin-embeddable','2.1.21')
sources=sorted((outroot/'source').rglob('*.kt'))
assert len(sources)==6
output=outroot/'classes'
if output.exists():shutil.rmtree(output)
output.mkdir(exist_ok=True)
command=['/workspace/toolchains/jdk-17.0.16+8/bin/java','-Xmx1g','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xplugin='+plugin,'-classpath',':'.join(cp),'-d',str(output),*map(str,sources)]
metadata={'scope':'Compile frozen actual071 ScannerApp, PickerCallbackSlots, NavigationState + actual074 cb04463 processing sources + actual001 OperationContext against real3b peers and real AAPT2-generated071 overlay R; no stubs/Gradle/device','production_commit':'071 frozen working bytes; 074 cb04463;001 final3b actual source','sources':{str(p.relative_to(outroot)):sha(p) for p in sources},'peer_build':str(app),'removed_old_peer_packages':['074 processing','071 replaced ScannerApp/navigation source classes','001 OperationContext source classes'],'peer_class_digest':hashlib.sha256(json.dumps(source_peer,sort_keys=True).encode()).hexdigest(),'peer_file_count':len(source_peer),'r_jar_sha256':sha(outroot/'R.jar'),'resource_inputs':json.loads((outroot/'resource-inputs.json').read_text()),'test_aars':aars,'command':command}
(outroot/'inputs.json').write_text(json.dumps(metadata,indent=2,sort_keys=True))
start=time.monotonic()
with (outroot/'compiler.log').open('w') as log: result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
metadata['exit_code']=result.returncode;metadata['elapsed_seconds']=round(time.monotonic()-start,3)
metadata['output_classes']={str(p.relative_to(output)):sha(p) for p in sorted(output.rglob('*.class'))}
metadata['compiled_class_count']=len(metadata['output_classes'])
(outroot/'result.json').write_text(json.dumps(metadata,indent=2,sort_keys=True))
print(json.dumps({k:metadata[k] for k in ['scope','production_commit','sources','peer_file_count','r_jar_sha256','exit_code','elapsed_seconds','compiled_class_count']},indent=2))
print((outroot/'compiler.log').read_text())
sys.exit(result.returncode)
