import java.util.Properties

val localProperties = java.util.Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "any_string" // GitHub doesn't require a specific username for PATs
                password = localProperties.getProperty("github_token") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "OOP"
include(":app")
include(":whisper-lib")
