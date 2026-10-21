// Inline via render_convention.py into :app (AGP8.11 lint crashes on applied Kotlin scripts). GroovyBuilder avoids Android plugin classloader duplication in applied Kotlin scripts.
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
val candidateTestBuildType = providers.gradleProperty("realityForgeTestBuildType").orElse("debug").get()
require(candidateTestBuildType in setOf("debug", "release")) { "realityForgeTestBuildType must be debug or release" }
candidateAndroid.setDsl("testBuildType", candidateTestBuildType)

// AndroidX test tracing retains these annotations; keep the reviewed version in the test APK.
dependencies { add("androidTestImplementation", "com.google.errorprone:error_prone_annotations:2.26.1") }

// White-box release instrumentation needs a finite external-DEX ABI surface.
// This explicit QA mode is NOT equivalent to the normal optimized shipping APK.
// Public AGP scoped-artifact transform; registered only for explicit QA AndroidTest.
abstract class NormalizeQaMonitorMetadata : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allJars: ListProperty<RegularFile>
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allDirectories: ListProperty<Directory>
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val normalizer: RegularFileProperty
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val abiHelper: RegularFileProperty
    @get:OutputFile
    abstract val outputClasses: RegularFileProperty
    @get:OutputFile
    abstract val normalizationReceipt: RegularFileProperty

    @TaskAction
    fun normalize() {
        val arguments = mutableListOf("python3", normalizer.get().asFile.absolutePath,
            "normalize", "--root", project.rootDir.absolutePath,
            "--output", outputClasses.get().asFile.absolutePath,
            "--receipt", normalizationReceipt.get().asFile.absolutePath)
        (allJars.get().map { it.asFile } + allDirectories.get().map { it.asFile })
            .distinct().sortedBy { it.absolutePath }.forEach {
                arguments.add("--input"); arguments.add(it.absolutePath)
            }
        project.exec { commandLine(arguments) }
    }
}

val instrumentationQa = providers.gradleProperty("realityForgeInstrumentationQa").orElse("false").get()
require(instrumentationQa in setOf("true", "false")) { "realityForgeInstrumentationQa must be true or false" }
if (instrumentationQa == "true") {
    require(candidateMode == "test" && candidateTestBuildType == "release") {
        "QA retention requires test signing and realityForgeTestBuildType=release"
    }
    val qaAndroidComponents = extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
    require(qaAndroidComponents.pluginVersion == com.android.build.api.AndroidPluginVersion(8, 11, 1)) {
        "QA metadata normalization requires reviewed AGP 8.11.1"
    }
    qaAndroidComponents.onVariants(qaAndroidComponents.selector().withBuildType("release")) { variant ->
        val testComponent = requireNotNull(variant.androidTest) { "QA requires the release AndroidTest component" }
        val normalizeMetadata = tasks.register<NormalizeQaMonitorMetadata>("normalizeQaMonitorMetadata") {
            normalizer.set(rootProject.layout.projectDirectory.file("tools/instrumentation_qa_metadata.py"))
            abiHelper.set(rootProject.layout.projectDirectory.file("tools/instrumentation_abi.py"))
            normalizationReceipt.set(layout.buildDirectory.file("generated/instrumentation-qa/metadata.json"))
        }
        testComponent.artifacts.forScope(com.android.build.api.variant.ScopedArtifacts.Scope.ALL)
            .use(normalizeMetadata)
            .toTransform(com.android.build.api.artifact.ScopedArtifact.CLASSES,
                NormalizeQaMonitorMetadata::allJars,
                NormalizeQaMonitorMetadata::allDirectories,
                NormalizeQaMonitorMetadata::outputClasses)
    }

    val receipt = layout.buildDirectory.file("generated/instrumentation-qa/manifest.json").get().asFile
    require(receipt.canonicalFile == receipt.absoluteFile.normalize()) { "QA manifest path must not contain aliases or symlinks" }
    val rules = receipt.parentFile.resolve("keep.pro")
    require(rules.canonicalFile == rules.absoluteFile.normalize()) { "QA rule path must not contain aliases or symlinks" }
    candidateTypes.getByName("release").withGroovyBuilder {
        "proguardFiles"(rules)
        "testProguardFiles"(rootProject.file("gradle/instrumentation-qa-test.pro"))
    }
    val verifyQaRetention = tasks.register<Exec>("verifyQaInstrumentationRetention") {
        // These pre-shrink producers were checked with AGP 8.11.1: neither
        // depends on test/app R8. Verify after them, never before parallel writes.
        dependsOn("compileReleaseJavaWithJavac", "compileReleaseAndroidTestJavaWithJavac", "normalizeQaMonitorMetadata")
        // Always verify: source, bytecode, rules and shrinker hashes must match.
        commandLine("python3", rootProject.file("tools/instrumentation_abi.py"),
            "verify", "--manifest", receipt, "--root", rootProject.projectDir)
    }
    tasks.matching { it.name in setOf("minifyReleaseWithR8", "minifyReleaseAndroidTestWithR8",
        "packageRelease", "packageReleaseAndroidTest") }.configureEach {
        dependsOn(verifyQaRetention)
    }
}
