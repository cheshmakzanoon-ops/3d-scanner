// :app convention; root settings must include(":macrobenchmark"). No additional plugin classloader.
import org.gradle.kotlin.dsl.withGroovyBuilder
fun Any.benchmarkDsl(name: String): Any = withGroovyBuilder { getProperty(name) } ?: error("Missing Android DSL property $name")
fun Any.setBenchmarkDsl(name: String, value: Any?) { withGroovyBuilder { setProperty(name, value) } }
val performanceAndroid = extensions.getByName("android")
@Suppress("UNCHECKED_CAST")
val performanceTypes = performanceAndroid.benchmarkDsl("buildTypes") as NamedDomainObjectContainer<Any>
@Suppress("UNCHECKED_CAST")
val performanceSigning = performanceAndroid.benchmarkDsl("signingConfigs") as NamedDomainObjectContainer<Any>
performanceTypes.maybeCreate("benchmark").apply {
    withGroovyBuilder { "initWith"(performanceTypes.getByName("release")) }
    setBenchmarkDsl("debuggable", false)
    setBenchmarkDsl("minifyEnabled", true)
    setBenchmarkDsl("shrinkResources", true)
    setBenchmarkDsl("signingConfig", performanceSigning.getByName("debug"))
    @Suppress("UNCHECKED_CAST")
    (benchmarkDsl("matchingFallbacks") as MutableList<String>).add("release")
}
@Suppress("UNCHECKED_CAST")
val performanceSources = performanceAndroid.benchmarkDsl("sourceSets") as NamedDomainObjectContainer<Any>
performanceSources.getByName("benchmark").apply {
    benchmarkDsl("manifest").withGroovyBuilder { "srcFile"(rootProject.file("macrobenchmark/target/AndroidManifest.xml")) }
    benchmarkDsl("java").withGroovyBuilder { "srcDir"(rootProject.file("macrobenchmark/target/java")) }
    benchmarkDsl("assets").withGroovyBuilder { "srcDir"(rootProject.file("macrobenchmark/target/assets")) }
}
