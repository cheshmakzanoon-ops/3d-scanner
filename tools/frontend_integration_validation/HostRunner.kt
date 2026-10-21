import java.io.File
import org.opencv.core.Core
fun main(args:Array<String>) {
 require(args.size==4){"Expected OpenCV,scanner engine,scanner shim,fixture directory"}
 val opencv=File(args[0]).canonicalFile;val engine=File(args[1]).canonicalFile;val shim=File(args[2]).canonicalFile
 System.load(opencv.path);System.load(engine.path);System.load(shim.path)
 System.setProperty("frontend.fixtures",File(args[3]).canonicalPath)
 check(Core.VERSION=="4.12.0"&&Core.getVersionString()=="4.12.0");Core.setNumThreads(2);check(Core.getNumThreads()<=2)
 fun maps(){
  val loaded=File("/proc/self/maps").readLines().mapNotNull{it.split(Regex("\\s+"),limit=6).getOrNull(5)}.filter{it.startsWith("/")}.map{File(it).canonicalPath}.toSet()
  val required=setOf(opencv.path,engine.path,shim.path);check(loaded.containsAll(required))
  check(loaded.filter{File(it).name.startsWith("libopencv")||File(it).name.startsWith("librealityforge")}.toSet()==required){"Unexpected native provider"}
 }
 maps()
 val result=org.junit.runner.JUnitCore.runClasses(com.realityforge.scanner.engine.features.ProductionFrontendHostTest::class.java,com.realityforge.scanner.export.LegacyPlyValidationTest::class.java,com.realityforge.scanner.export.LegacyPlyHardeningTest::class.java)
 result.failures.forEach{System.err.println(it.trace)}
 println("Production frontend HOST integration: ${result.runCount}, failures: ${result.failureCount}; OpenCV=${Core.getVersionString()}")
 maps();check(result.runCount==11&&result.wasSuccessful())
}
