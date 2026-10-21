import java.io.File

fun main(args:Array<String>) {
    require(args.size==2) { "Expected shim and host-engine absolute paths" }
    val shim=File(args[0]).canonicalFile
    val engine=File(args[1]).canonicalFile
    // Preload the exact validated engine before the JNI shim resolves its dependency.
    System.load(engine.path)
    System.load(shim.path)
    val maps=File("/proc/self/maps").readLines()
    val loaded=maps.mapNotNull { line -> line.split(Regex("\\s+"),limit=6).getOrNull(5) }
        .filter { it.startsWith("/") }.map { File(it).canonicalPath }.toSet()
    check(engine.path in loaded && shim.path in loaded) { "Expected native inputs not mapped" }
    check(loaded.filter { File(it).name==engine.name }.all { it==engine.path }) {
        "Unexpected alternate host engine loaded"
    }
    val result=org.junit.runner.JUnitCore.runClasses(com.realityforge.scanner.engine.ProductionWrapperHostTest::class.java,
        com.realityforge.scanner.work.execution.ReconstructionAttemptCleanupHostTest::class.java)
    result.failures.forEach { System.err.println(it.trace) }
    println("New production-wrapper host JNI tests: ${result.runCount}, failures: ${result.failureCount}")
    check(result.runCount==13 && result.wasSuccessful())
}
