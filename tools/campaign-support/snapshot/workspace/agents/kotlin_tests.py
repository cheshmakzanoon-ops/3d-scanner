from pathlib import Path
import subprocess,sys,os
cache=Path('/workspace/.gradle/caches/modules-2/files-2.1')
def jar(group,name,version): return str(next((cache/group/name/version).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.21'); junit=jar('junit','junit','4.13.2'); ham=jar('org.hamcrest','hamcrest-core','1.3')
annotations=str(next((cache/'org.jetbrains'/'annotations').glob('*/*/*.jar')))
cp=':'.join([stdlib,junit,ham,annotations])
compiler=':'.join([jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.21'),stdlib,jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.21'),jar('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.1.21'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),annotations])
root=Path(sys.argv[1]);relative=sys.argv[2].split(',');tests=sys.argv[3].split(',');out=root/'build'/'agent-jvm';out.mkdir(parents=True,exist_ok=True)
sources=[str(p) for s in relative for p in ([root/s] if (root/s).is_file() else (root/s).rglob('*.kt'))]
java='/workspace/toolchains/jdk-17.0.17+10/bin/java'
subprocess.run([java,'-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(out),*sources],check=True)
subprocess.run([java,'-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore',*tests],check=True)
