pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Kotlin marker metadata differs between Central and the Plugin Portal.
        // Preserve the verified Central bytes and fail closed if unavailable.
        exclusiveContent {
            forRepository {
                maven {
                    name = "KotlinPluginMarkerMavenCentral"
                    url = uri("https://repo.maven.apache.org/maven2/")
                }
            }
            filter {
                includeModule("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin")
                includeModule("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "RealityForge"
include(":app")
include(":macrobenchmark")
