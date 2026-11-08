package com.titipong.pdfwriter

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }

    companion object {
        /** Outlives the activity, so a PDF write started when leaving the app still finishes. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
