from pathlib import Path
import subprocess,sys,zipfile
cache=Path('/workspace/.gradle/caches/modules-2/files-2.1')
def jar(group,name,version): return str(next((cache/group/name/version).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.21')
annotations=str(next((cache/'org.jetbrains'/'annotations').glob('*/*/*.jar')))
compiler=':'.join([jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.21'),stdlib,jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.21'),jar('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.1.21'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),annotations])
root=Path(sys.argv[1]);out=root/'build'/'agent-source-compile';out.mkdir(parents=True,exist_ok=True)
libs=[stdlib,annotations,'/workspace/android-sdk/platforms/android-36/android.jar']
for group,name,version in [('org.opencv','opencv','4.12.0'),('androidx.exifinterface','exifinterface','1.4.1')]:
 aar=next((cache/group/name/version).glob('*/*.aar'));target=out/(name+'-classes.jar')
 with zipfile.ZipFile(aar) as f:target.write_bytes(f.read('classes.jar'))
 libs.append(str(target))
libs.append(jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'))
libs.extend([jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3')])
monitor=next((cache/'androidx.test'/'monitor').glob('*/*/*.aar'))
with zipfile.ZipFile(monitor) as f:(out/'monitor-classes.jar').write_bytes(f.read('classes.jar'))
libs.append(str(out/'monitor-classes.jar'))
sources=[str(p) for s in sys.argv[2].split(',') for p in ([root/s] if (root/s).is_file() else (root/s).rglob('*.kt'))]
subprocess.run(['/workspace/toolchains/jdk-17.0.17+10/bin/java','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(libs),'-d',str(out/'classes'),*sources],check=True)
print('Actual Android source dependency compilation passed:',len(sources),'Kotlin files')

if len(sys.argv)>3:
 subprocess.run(['/workspace/toolchains/jdk-17.0.17+10/bin/java','-cp',str(out/'classes')+':'+':'.join(libs),'org.junit.runner.JUnitCore',*sys.argv[3].split(',')],check=True)
