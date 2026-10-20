from pathlib import Path
import subprocess,os
cache=Path('/workspace/toolchains/gradle-user/caches/modules-2/files-2.1')
def jar(group,artifact,version):
 return str(next((cache/group/artifact/version).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.21')
coroutines=jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2')
annotations=jar('org.jetbrains','annotations','23.0.0')
junit=jar('junit','junit','4.13.2');hamcrest=jar('org.hamcrest','hamcrest-core','1.3')
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.21'),stdlib,coroutines,annotations,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.21'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330')]
java='/workspace/toolchains/jdk-17.0.16+8/bin/java'
cp=[stdlib,coroutines,annotations,junit,hamcrest,'/workspace/campaign-061-080/071-processing-ack/peer-classes','/workspace/toolchains/android-sdk/platforms/android-36/android.jar']
cacheRoot=Path('/workspace/toolchains/gradle-user/caches/8.13/transforms')
cp+=map(str,[p for p in cacheRoot.rglob('*.jar') if 'instrumented' not in p.parts and (p.name=='classes.jar' or p.name.endswith('-runtime.jar'))])
base=Path('/workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/source/app/build')
cp+=[str(base/'intermediates/javac/debug/compileDebugJavaWithJavac/classes'),str(base/'intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar')]
cp+=map(str,[p for group in cache.glob('androidx.*') for p in group.rglob('*.jar')])
plugin=jar('org.jetbrains.kotlin','kotlin-compose-compiler-plugin-embeddable','2.1.21')
sources=['/workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/source/app/src/main/java/com/realityforge/scanner/domain/OperationContext.kt','/workspace/agent-work/071/app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt','/workspace/agent-work/074/app/src/main/java/com/realityforge/scanner/ui/processing/ProcessingScreen.kt','/workspace/agent-work/074/app/src/main/java/com/realityforge/scanner/ui/processing/JobUiState.kt']
out=Path('/workspace/campaign-061-080/071-processing-ack/compose-classes');out.mkdir(exist_ok=True)
subprocess.run([java,'-Xmx1g','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xplugin='+plugin,'-classpath',':'.join(cp),'-d',str(out),*sources],check=True)
print('PASS: actual071ScannerApp+actual074ProcessingScreen/JobUiState compile with Kotlin/Compose2.1.21 against real pinned Android36 and a18 peer classes; no source stubs')
