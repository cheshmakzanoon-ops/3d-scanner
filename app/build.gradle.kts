import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import org.gradle.kotlin.dsl.withGroovyBuilder
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.realityforge.scanner"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.realityforge.scanner"
        minSdk = 29
        targetSdk = 36
        versionCode = 19
        versionName = "0.19.0-m19"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++20" } }
    }
    externalNativeBuild { cmake { path = file("../CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false // Enable only after JNI/R8 integration tests exist.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // Synthetic photographic regression fixtures are test APK assets, never release assets.
    sourceSets.getByName("androidTest").assets.srcDir("../benchmarks/fixtures")
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.google.android.filament:filament-android:1.56.0")
    implementation("com.google.android.filament:gltfio-android:1.56.0")
    testImplementation("junit:junit:4.13.2")
    // HOST Room integration only; API 35 matches the reviewed cached framework.
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Android's mockable JSONObject throws in local tests; exercise the real JSON codec.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Align the screenshot API with Test core 1.6.x; Compose pulls older Espresso.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Consume095 approved module graph locks in ordinary Gradle builds, including CI.
// Managed names match095 export.gradle at c2f775796913a35c65f22c6210641eaa95805432.
val lockedAndroidConfigurations = setOf(
    "releaseRuntimeClasspath",
    "releaseCompileClasspath",
    "kspReleaseKotlinProcessorClasspath",
    "releaseAndroidTestRuntimeClasspath",
    "releaseAndroidTestCompileClasspath",
    "releaseUnitTestRuntimeClasspath",
    "releaseUnitTestCompileClasspath",
    "debugRuntimeClasspath",
    "debugCompileClasspath",
    "kspDebugKotlinProcessorClasspath",
    "debugAndroidTestRuntimeClasspath",
    "debugAndroidTestCompileClasspath",
    "debugUnitTestRuntimeClasspath",
    "debugUnitTestCompileClasspath"
)
configurations.configureEach {
    if (name in lockedAndroidConfigurations) resolutionStrategy.activateDependencyLocking()
}

// Inline convention from gradle/release-validation.gradle.kts (AGP lint applied-script UAST workaround)
// Inline via render_convention.py into :app (AGP8.11 lint crashes on applied Kotlin scripts). GroovyBuilder avoids Android plugin classloader duplication in applied Kotlin scripts.
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

// Explicit HOST-only gate. The regular JVM suite never resolves a Robolectric SDK.
val robolectricSdk = configurations.create("robolectricSdk") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    resolutionStrategy.activateDependencyLocking()
}
dependencies { add(robolectricSdk.name, "org.robolectric:android-all:15-robolectric-12650502") }
val hostSdkDirectory = layout.buildDirectory.dir("robolectric-sdk")
val prepareHostIntegrationSdk = tasks.register<Sync>("prepareHostIntegrationSdk") {
    from(robolectricSdk)
    into(hostSdkDirectory)
}
// No outputs: verify source and staged bytes on every HOST invocation, even after Sync is up-to-date.
val verifyHostIntegrationSdk = tasks.register("verifyHostIntegrationSdk") {
    dependsOn(prepareHostIntegrationSdk)
    doLast {
        val canonical = "android-all-15-robolectric-12650502.jar"
        val expected = "6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b"
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val source = robolectricSdk.singleFile
        val staged = hostSdkDirectory.get().file(canonical).asFile
        check(source.name == canonical && digest(source) == expected) { "Unexpected verified HOST SDK source." }
        check(staged.isFile && digest(staged) == expected) { "HOST SDK staging bytes do not match the pinned framework." }
        check(hostSdkDirectory.get().asFile.listFiles().orEmpty().map { it.name } == listOf(canonical)) {
            "Unexpected file in the offline HOST SDK directory."
        }
    }
}
val verifyHostIntegrationClassification = tasks.register("verifyHostIntegrationClassification") {
    val sources = fileTree("src/test") { include("**/*.kt", "**/*.java") }
    inputs.files(sources)
    doLast {
        val prefix = "com.realityforge.scanner.hostintegration"
        val hosts = sources.files.filter { it.readText().contains("org.robolectric") }
        check(hosts.isNotEmpty()) { "The explicit HOST integration gate has no Robolectric sources." }
        hosts.forEach { source ->
            val text = source.readText()
            check(source.invariantSeparatorsPath.contains("/com/realityforge/scanner/hostintegration/") &&
                Regex("(?m)^package\\s+" + Regex.escape(prefix) + "(?:[.;\\s]|$)").containsMatchIn(text)) {
                "Robolectric source must be classified in $prefix: $source"
            }
        }
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(verifyHostIntegrationClassification)
    // Enforce offline resolution even if an unclassified test slips through discovery.
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.usePreinstrumentedJars", "false")
    systemProperty("robolectric.dependency.dir", hostSdkDirectory.get().asFile.absolutePath)
    doFirst {
        check(!systemProperties.containsKey("robolectric-deps.properties")) {
            "An alternate Robolectric dependency map is not permitted."
        }
    }
    if (name != "hostIntegrationTest") exclude("**/hostintegration/**")
}
// Independent JDK pixel oracle: HOST test input only, never an Android asset or decoder replacement.
val decoderOracleSource = rootProject.file("tools/GenerateDecoderOracle.java")
val decoderOracleSourceSha256 = "43270ecd69a0e59ceabff1a65e039bc38f92c692fa4ffd77f15939ca14d916ff"
val decoderFixtureDirectory = rootProject.file("benchmarks/fixtures/relief")
val decoderFixtureHashes = linkedMapOf(
    "view-01.jpg" to "66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c",
    "view-02.jpg" to "c275db33a1d10d84fe5a9bf78c55a7ea31de002ac305e00fd521e54b351b391b",
    "view-03.jpg" to "a0d386746c2a8f47840058613bc89aa3c4a8095995288fa936d368ad67fe6c0f",
)
val decoderOracleDirectory = layout.buildDirectory.dir("host-decoder-oracle")
val decoderJava = extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>().launcherFor {
    languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
}
fun decoderSha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 255) }
fun verifyDecoderOracleInputs() {
    check(decoderOracleSource.length() in 1..65536 && decoderSha(decoderOracleSource) == decoderOracleSourceSha256)
    decoderFixtureHashes.forEach { (name, hash) ->
        val source = decoderFixtureDirectory.resolve(name)
        check(source.length() in 1..1048576 && decoderSha(source) == hash) { "Decoder fixture bytes changed: $name" }
    }
}
val generateHostDecoderOracle = tasks.register<Exec>("generateHostDecoderOracle") {
    group = "verification"
    description = "Generate independent HOST-only ImageIO reference pixels with JDK 17."
    inputs.file(decoderOracleSource).withPropertyName("generatorSource").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(decoderFixtureHashes.keys.map { decoderFixtureDirectory.resolve(it) })
        .withPropertyName("decoderFixtureJpegs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(decoderJava.map { it.executablePath }).withPropertyName("oracleJavaExecutable")
    inputs.property("oracleJavaRuntime", decoderJava.map { it.metadata.javaRuntimeVersion })
    outputs.dir(decoderOracleDirectory)
    timeout.set(Duration.ofSeconds(120))
    doFirst {
        verifyDecoderOracleInputs()
        val output = decoderOracleDirectory.get().asFile
        check(output == layout.buildDirectory.dir("host-decoder-oracle").get().asFile)
        check(output.canonicalFile == output.absoluteFile.normalize()) { "Oracle output may not be a symlink or aliased path." }
        // Recreate only this task's generated directory; reject symlink descendants before deletion.
        if (output.exists()) {
            check(output.isDirectory)
            check(output.walkTopDown().none { Files.isSymbolicLink(it.toPath()) })
            check(output.deleteRecursively()) { "Could not clean generated HOST oracle output." }
        }
        check(output.parentFile.isDirectory || output.parentFile.mkdirs())
        commandLine(decoderJava.get().executablePath.asFile, "-Xmx256m", "-Djava.awt.headless=true",
            decoderOracleSource, decoderFixtureDirectory, output, decoderOracleSource)
    }
}
// Always verify even if generation/Test is up-to-date; no successful stale/tampered oracle evidence.
val verifyHostDecoderOracle = tasks.register("verifyHostDecoderOracle") {
    dependsOn(generateHostDecoderOracle)
    doLast {
        verifyDecoderOracleInputs()
        val directory = decoderOracleDirectory.get().asFile
        check(directory.canonicalFile == directory.absoluteFile.normalize())
        val manifest = directory.resolve("SHA256SUMS")
        check(!Files.isSymbolicLink(manifest.toPath()))
        check(manifest.isFile && manifest.length() in 1..4096)
        val lines = manifest.readLines()
        check(lines.size == 12)
        check(lines[0] == "generatorSha256=$decoderOracleSourceSha256")
        check(lines[1] == "javaRuntime=${decoderJava.get().metadata.javaRuntimeVersion}")
        check(lines[2] == "layout=RGB8-row-major")
        val expectedNames = decoderFixtureHashes.keys.flatMap { name ->
            listOf(800 to 600, 256 to 192, 512 to 384).map { (w, h) -> "$name.${w}x${h}.rgb" }
        }.toSet()
        val entries = lines.drop(3).map { it.split("  ") }
        check(entries.all { it.size == 2 && it[0].matches(Regex("[0-9a-f]{64}")) })
        check(entries.map { it[1] }.toSet() == expectedNames)
        check(directory.listFiles().orEmpty().map { it.name }.toSet() == expectedNames + "SHA256SUMS")
        entries.forEach { (hash, name) ->
            val reference = directory.resolve(name)
            check(!Files.isSymbolicLink(reference.toPath()))
            val size = name.substringAfter(".jpg.").substringBefore(".rgb").split("x").map { it.toInt() }
            check(reference.length() == 78L + size[0].toLong() * size[1] * 3)
            check(decoderSha(reference) == hash)
        }
    }
}

tasks.register<Test>("hostIntegrationTest") {
    group = "verification"
    description = "HOST-only Room, import, export and decoder integration; not Android device qualification."
    // Read the task itself, not a mapped TaskProvider: mapped providers carry the
    // ordinary Test as a producer and would accidentally execute both gates.
    val ordinary = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = ordinary.testClassesDirs
    classpath = ordinary.classpath
    dependsOn(ordinary.taskDependencies.getDependencies(ordinary))
    dependsOn(verifyHostIntegrationSdk, verifyHostDecoderOracle)
    inputs.dir(decoderOracleDirectory).withPropertyName("decoderOraclePixels").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("rf.decoder.fixtureDir", decoderFixtureDirectory.absolutePath)
    systemProperty("rf.decoder.oracleDir", decoderOracleDirectory.get().asFile.absolutePath)
    systemProperty("rf.decoder.oracleGeneratorSha256", decoderOracleSourceSha256)
    // Pinned Robolectric FileDescriptorInterceptor requires this observed JDK17 reflection boundary.
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    doFirst {
        systemProperty("rf.decoder.oracleManifestSha256", decoderSha(decoderOracleDirectory.get().file("SHA256SUMS").asFile))
    }
    include("**/hostintegration/**")
    filter {
        includeTestsMatching("com.realityforge.scanner.hostintegration.*")
        isFailOnNoMatchingTests = true
    }
    maxParallelForks = 1
    maxHeapSize = "1g"
    timeout.set(Duration.ofMinutes(5))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/hostIntegrationTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/hostIntegrationTest"))
}
