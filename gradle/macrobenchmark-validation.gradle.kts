// :app convention. Root settings must include(":macrobenchmark").
import com.android.build.gradle.AppExtension
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools.build:gradle:8.11.1") }
}
extensions.configure<AppExtension> {
    buildTypes.maybeCreate("benchmark").apply {
        initWith(buildTypes.getByName("release"))
        isDebuggable = false
        isMinifyEnabled = true
        isShrinkResources = true
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    sourceSets.getByName("benchmark") {
        manifest.srcFile(rootProject.file("macrobenchmark/target/AndroidManifest.xml"))
        java.srcDir(rootProject.file("macrobenchmark/target/java"))
        assets.srcDir(rootProject.file("macrobenchmark/target/assets"))
    }
}
