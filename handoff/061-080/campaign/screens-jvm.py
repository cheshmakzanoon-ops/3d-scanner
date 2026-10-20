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
cp=[stdlib,coroutines,annotations,junit,hamcrest,'/workspace/agent-work/072/app/build/tmp/kotlin-classes/debug','/workspace/toolchains/android-sdk/platforms/android-36/android.jar']
sources=[]
for role,files in {
 '072':['ui/library/LibraryState.kt','ui/library/LibraryPresenter.kt'],
 '073':['ui/photoreview/ReviewState.kt','ui/photoreview/PhotoCurationPresenter.kt'],
 '074':['ui/processing/JobUiState.kt','ui/processing/DurableJobUiAdapter.kt'],
 '075':['ui/onboarding/CaptureGuide.kt'],
 '079':['preferences/PreferencesStore.kt']}.items():
 for f in files:sources.append(f'/workspace/agent-work/{role}/app/src/main/java/com/realityforge/scanner/{f}')
 sources+=map(str,Path(f'/workspace/agent-work/{role}/app/src/test/java/com/realityforge/scanner/agents/a{role}').glob('*.kt'))
sources+=map(str,Path('/workspace/campaign-061-080/071-service-dependencies').rglob('*.kt'))
out=Path('/workspace/campaign-061-080/screens-jvm-classes');out.mkdir(exist_ok=True)
command=[java,'-Xmx1g','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(cp),'-d',str(out),*sources]
print('Compile production adapters and real peer contracts with Kotlin2.1.21/JDK17',flush=True)
subprocess.run(command,check=True)
tests=['a072.LibraryQueryTest','a072.LibraryPresenterTest','a073.ReviewAdmissionTest','a073.PhotoCurationPresenterTest','a074.JobActionTest','a074.DurableJobUiAdapterTest','a075.CaptureGuideTest','a079.PreferencesStoreTest']
subprocess.run([java,'-cp',':'.join([str(out),*cp]),'org.junit.runner.JUnitCore',*[f'com.realityforge.scanner.agents.{t}' for t in tests]],check=True)
