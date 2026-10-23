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
 val result=org.junit.runner.JUnitCore.runClasses(com.realityforge.scanner.engine.features.ProductionExtractorHostTest::class.java)
 result.failures.forEach{System.err.println(it.trace)}
 println("Production extractor HOST JNI tests: ${result.runCount}, failures: ${result.failureCount}; native=${Core.getVersionString()}, threads=${Core.getNumThreads()}")
 maps();check(result.runCount==11 && result.wasSuccessful())
}
