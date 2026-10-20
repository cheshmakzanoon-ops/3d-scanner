// Apply from :app. GroovyBuilder avoids Android plugin classloader duplication in applied Kotlin scripts.
import org.gradle.kotlin.dsl.withGroovyBuilder
@Suppress("UNCHECKED_CAST")
fun Any.dsl(name: String): Any = withGroovyBuilder { getProperty(name) } ?: error("Missing Android DSL property $name")
fun Any.setDsl(name: String, value: Any?) { withGroovyBuilder { setProperty(name, value) } }
val candidateAndroid = extensions.getByName("android")
@Suppress("UNCHECKED_CAST")
val candidateSigning = candidateAndroid.dsl("signingConfigs") as NamedDomainObjectContainer<Any>
val candidateMode = providers.gradleProperty("candidateSigning").orElse("test").get()
require(candidateMode in setOf("test", "production")) { "candidateSigning must be test or production" }
val candidateSigner = if (candidateMode == "test") candidateSigning.getByName("debug") else {
    val required = listOf("RF_RELEASE_STORE_FILE", "RF_RELEASE_STORE_PASSWORD", "RF_RELEASE_KEY_ALIAS", "RF_RELEASE_KEY_PASSWORD")
    require(required.all { !System.getenv(it).isNullOrBlank() }) {
        "BLOCKED_EXTERNAL: production signing credentials must be injected externally"
    }
    candidateSigning.maybeCreate("externalCandidate").apply {
        setDsl("storeFile", file(System.getenv("RF_RELEASE_STORE_FILE")))
        setDsl("storePassword", System.getenv("RF_RELEASE_STORE_PASSWORD"))
        setDsl("keyAlias", System.getenv("RF_RELEASE_KEY_ALIAS"))
        setDsl("keyPassword", System.getenv("RF_RELEASE_KEY_PASSWORD"))
    }
}
@Suppress("UNCHECKED_CAST")
val candidateTypes = candidateAndroid.dsl("buildTypes") as NamedDomainObjectContainer<Any>
candidateTypes.getByName("release").apply {
    setDsl("debuggable", false)
    setDsl("minifyEnabled", true)
    setDsl("shrinkResources", true)
    setDsl("signingConfig", candidateSigner)
    dsl("ndk").setDsl("debugSymbolLevel", "FULL")
}
candidateAndroid.setDsl("testBuildType", "release")
