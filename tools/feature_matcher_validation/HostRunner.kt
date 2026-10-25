import java.io.File
import org.opencv.core.Core
fun main(args:Array<String>) {
 require(args.size==1)
 val lib=File(args[0]).canonicalFile;System.load(lib.path)
 check(Core.VERSION=="4.12.0" && Core.getVersionString()=="4.12.0")
 Core.setNumThreads(2);check(Core.getNumThreads()<=2)
 fun maps(){
  val loaded=File("/proc/self/maps").readLines().mapNotNull{it.split(Regex("\\s+"),limit=6).getOrNull(5)}.filter{it.contains("libopencv")}.toSet()
  check(loaded==setOf(lib.path)){"Unexpected OpenCV mapping: $loaded"}
 }
 maps()
 val result=org.junit.runner.JUnitCore.runClasses(com.realityforge.scanner.engine.matching.ProductionMatcherHostTest::class.java)
 result.failures.forEach{System.err.println(it.trace)}
 println("Production matcher HOST JNI tests: ${result.runCount}, failures: ${result.failureCount}; native=${Core.getVersionString()}, threads=${Core.getNumThreads()}")
 maps();check(result.runCount==9)
 // JUnit captures Errors and cancellation too; those are not ordinary regressions.
 check(result.failures.all { it.exception is AssertionError }) {
  "Unexpected matcher test throwable; native/resource/cancellation failures remain fatal"
 }
 // Only a completed, correctly counted JUnit run with intact native mappings may
 // signal an ordinary regression failure to the source-bound supplementary adapter.
 if(!result.wasSuccessful())kotlin.system.exitProcess(10)
}
