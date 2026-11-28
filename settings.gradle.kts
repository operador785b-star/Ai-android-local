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
        if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        } else {
            mavenCentral()
        }
    }
}
rootProject.name = "IA Local Android"
include(":app")
