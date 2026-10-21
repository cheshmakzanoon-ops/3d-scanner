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
