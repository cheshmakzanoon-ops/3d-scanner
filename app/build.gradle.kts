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

// AndroidX test tracing retains these annotations; provide actual classes for test R8.
dependencies { add("androidTestCompileOnly", "com.google.errorprone:error_prone_annotations:2.26.1") }
