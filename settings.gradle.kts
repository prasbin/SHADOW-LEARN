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
        // Google's Maven Central mirror first: repo.maven.apache.org was
        // measured unusably slow from this network (KB/s trickle), while the
        // mirror serves the identical artifacts at full speed.
        // See docs/SETUP.md. mavenCentral() stays as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}
rootProject.name = "SHADOW LEARN"
include(":app")
