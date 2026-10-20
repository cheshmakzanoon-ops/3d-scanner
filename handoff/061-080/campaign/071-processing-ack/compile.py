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
cp=[stdlib,coroutines,annotations,junit,hamcrest,'/workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/source/app/build/tmp/kotlin-classes/debug','/workspace/toolchains/android-sdk/platforms/android-36/android.jar']
sources=['/workspace/campaign-061-080/071-processing-ack/ActualDispatchBody.kt']
out=Path('/workspace/campaign-061-080/071-processing-ack/classes');out.mkdir(exist_ok=True)
subprocess.run([java,'-Xmx512m','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(cp),'-d',str(out),*sources],check=True)
print('PASS: exact production callback body typechecks as suspend(JobAction)->Boolean against actual a18 compiled model/feature contracts')
