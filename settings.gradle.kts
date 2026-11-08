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
        maven { url = uri("https://jitpack.io") } // Tesseract4Android (OCR)
        maven { url = uri("https://maven.ghostscript.com") } // MuPDF (page rendering)
    }
}
rootProject.name = "PDFWriter"
include(":app")
