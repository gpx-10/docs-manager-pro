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
        // MuPDF (Artifex) publishes its Android library here.
        maven("https://maven.ghostscript.com")
    }
}
rootProject.name = "DocsManagerPro"
include(":app")
