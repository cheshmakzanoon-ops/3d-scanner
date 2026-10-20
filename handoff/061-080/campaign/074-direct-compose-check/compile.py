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
  if p.suffix not in ['.class','.kotlin_module']:continue
  dest=peer/name/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest)
  source_peer[f'{name}/{rel}']=sha(dest)
cp=[stdlib,coroutines,annotations,jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3'),str(peer/'app'),str(peer/'test'),'/workspace/toolchains/android-sdk/platforms/android-36/android.jar']
transforms=Path('/workspace/toolchains/gradle-user/caches/8.13/transforms')
cp+=map(str,sorted(p for p in transforms.rglob('*.jar') if 'instrumented' not in p.parts and (p.name=='classes.jar' or p.name.endswith('-runtime.jar'))))
cp+=[str(app/'intermediates/javac/debug/compileDebugJavaWithJavac/classes'),str(app/'intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar')]
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
expected_test='309afde2056ca50bba7975b902dd7bb66e654eacd7ae3681444cd7edab360d55'
assert sha(next(p for p in sources if p.name=='ScreenInteractionTest.kt'))==expected_test
output=outroot/'classes';output.mkdir(exist_ok=True)
command=['/workspace/toolchains/jdk-17.0.16+8/bin/java','-Xmx1g','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xplugin='+plugin,'-classpath',':'.join(cp),'-d',str(output),*map(str,sources)]
metadata={'scope':'Compile actual074 JobUiState,ProcessingScreen and frozen Android ScreenInteractionTest together; no source stubs, no Gradle or device calls','production_commit':'cb04463','sources':{str(p.relative_to(outroot)):sha(p) for p in sources},'peer_build':str(app),'removed_old_peer_packages':['ui/processing','agents/a074'],'peer_class_digest':hashlib.sha256(json.dumps(source_peer,sort_keys=True).encode()).hexdigest(),'peer_file_count':len(source_peer),'r_jar_sha256':sha(app/'intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'),'test_aars':aars,'command':command}
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
