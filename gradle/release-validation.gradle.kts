// Apply from :app after Android application plugin. No secret values enter task inputs/logs.
import com.android.build.gradle.AppExtension
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools.build:gradle:8.11.1") }
}
extensions.configure<AppExtension> {
    val mode = providers.gradleProperty("candidateSigning").orElse("test").get()
    require(mode in setOf("test", "production")) { "candidateSigning must be test or production" }
    val signer = if (mode == "test") signingConfigs.getByName("debug") else {
        val required = listOf("RF_RELEASE_STORE_FILE", "RF_RELEASE_STORE_PASSWORD", "RF_RELEASE_KEY_ALIAS", "RF_RELEASE_KEY_PASSWORD")
        require(required.all { !System.getenv(it).isNullOrBlank() }) {
            "BLOCKED_EXTERNAL: production signing credentials must be injected externally"
        }
        signingConfigs.maybeCreate("externalCandidate").apply {
            storeFile = file(System.getenv("RF_RELEASE_STORE_FILE"))
            storePassword = System.getenv("RF_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RF_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RF_RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        isMinifyEnabled = true
        isShrinkResources = true
        signingConfig = signer
        ndk.debugSymbolLevel = "FULL"
    }
    testBuildType = "release"
}
