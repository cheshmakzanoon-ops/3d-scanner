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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Agent 004 one-time transport: CI obtains the official tagged wrapper, verifies the
// published SHA-256, and exposes it only as a build report artifact. The final branch
// removes this task after committing the verified wrapper into source.
val agent004OfficialWrapper = layout.buildDirectory.file("reports/agent004/gradle-wrapper.jar")
val materializeAgent004OfficialWrapper = tasks.register("materializeAgent004OfficialWrapper") {
    outputs.file(agent004OfficialWrapper)
    doLast {
        val expected = "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
        val target = agent004OfficialWrapper.get().asFile
        target.parentFile.mkdirs()
        val connection = java.net.URI(
            "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
        ).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        connection.getInputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        check(actual == expected) {
            "Official Gradle wrapper checksum mismatch: expected=$expected actual=$actual"
        }
        target.resolveSibling("gradle-wrapper.sha256.txt").writeText("$actual  gradle-wrapper.jar\n")
    }
}
tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(materializeAgent004OfficialWrapper)
    }
}
