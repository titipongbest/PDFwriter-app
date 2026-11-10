package com.titipong.pdfwriter

import java.io.File

/** Where the app keeps its own files (%APPDATA%\PDFWriter on Windows). */
object AppDirs {
    val data: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".pdfwriter")
        File(base, "PDFWriter").apply { mkdirs() }
    }
    val cache: File by lazy { File(data, "cache").apply { mkdirs() } }
    val images: File by lazy { File(data, "images").apply { mkdirs() } }
    val docs: File by lazy { File(data, "docs").apply { mkdirs() } }

    /** Files shipped next to the app (tessdata, handwriting helper); falls back to the working dir. */
    val resources: File by lazy {
        System.getProperty("compose.application.resources.dir")?.let { File(it) } ?: File("appResources")
    }
}
