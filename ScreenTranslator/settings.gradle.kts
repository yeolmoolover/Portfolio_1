pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central (Maven Central itself rate-limits bursts with 429)
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google-hosted mirror of Maven Central (Maven Central itself rate-limits bursts with 429)
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "ScreenTranslator"
include(":app")
