import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

group = "com.titipong.pdfwriter"
version = "1.0.0"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.apache.pdfbox:pdfbox:2.0.32")
    implementation("org.json:json:20240303")
    implementation("net.sourceforge.tess4j:tess4j:5.13.0") {
        // Tess4J brings PDFBox 3 for its own PDF helpers (unused here); the app uses PDFBox 2.
        exclude(group = "org.apache.pdfbox")
        exclude(group = "org.ghost4j")
        exclude(group = "org.jboss")
    }
}

compose.desktop {
    application {
        mainClass = "com.titipong.pdfwriter.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8", "-Dsun.java2d.d3d=true")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "PDFWriter"
            packageVersion = "1.0.0"
            description = "PDF reader and annotator"
            vendor = "NorthXbar"
            copyright = "NorthXbar"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            modules("java.desktop", "java.naming", "java.sql", "jdk.unsupported", "java.management", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("icons/app.ico"))
                menuGroup = "PDFWriter"
                shortcut = true
                dirChooser = true
                upgradeUuid = "8d3c6f2e-4b1a-4f7e-9a51-2f0c3b7d9e41"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
