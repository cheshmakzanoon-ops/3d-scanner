from pathlib import Path
import hashlib,json,subprocess
cache=Path('/workspace/toolchains/gradle-user/caches/modules-2/files-2.1')
def jar(group,name,version): return str(next((cache/group/name/version).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.21'); coroutines=jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'); annotations=jar('org.jetbrains','annotations','23.0.0')
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.21'),stdlib,coroutines,annotations,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.21'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330')]
cp=[stdlib,coroutines,annotations,jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3'),'/workspace/campaign-061-080/final-validation/3b9f9157753b/source/app/build/tmp/kotlin-classes/debug','/workspace/campaign-061-080/final-validation/3b9f9157753b/source/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','/workspace/toolchains/android-sdk/platforms/android-36/android.jar']
cp+=map(str,sorted(Path('/workspace/toolchains/gradle-user/caches/8.13/transforms').glob('*/transformed/*/jars/classes.jar')))
cp+=map(str,sorted(cache.glob('androidx.*/*/*/*/*.jar')))
cp.append('/workspace/campaign-061-080/final-validation/3b9f9157753b/source/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar')
source=Path('/workspace/agent-work/077/app/src/androidTest/java/com/realityforge/scanner/agents/a077/ActualAccessibilityTest.kt')
out=Path('/workspace/campaign-061-080/077-guide-scroll-classes');out.mkdir(exist_ok=True)
command=['/workspace/toolchains/jdk-17.0.16+8/bin/java','-Xmx1g','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(cp),'-d',str(out),str(source)]
Path('/workspace/campaign-061-080/077-guide-scroll-compile-command.json').write_text(json.dumps(dict(command=command,source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),purpose='Focused real Kotlin compilation against immutable 3b9f9157753b actual Android app/R output and actual cached Android/test dependency classes; does not replace final Gradle/device gate.'),indent=2)+'\n')
print('Kotlin2.1.21/JDK17; ActualAccessibilityTest source SHA256 '+hashlib.sha256(source.read_bytes()).hexdigest(),flush=True)
subprocess.run(command,check=True)
print('PASS: focused actual guide scrolling and named-field readiness Android/R/JUnit compilation',flush=True)
