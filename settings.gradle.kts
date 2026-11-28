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
        // Tesseract4Android (offline OCR) is built from its GitHub releases here; only that group is taken from JitPack.
        maven("https://jitpack.io") { content { includeGroup("cz.adaptech.tesseract4android") } }
    }
}
rootProject.name = "DocsManagerPro"
include(":app")
