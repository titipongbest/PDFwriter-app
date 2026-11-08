package com.titipong.pdfwriter

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a pass/fail test: opens test PDFs, scrolls like a user (scrub far away, come back) and logs
 * timings (logcat tag PWPERF) plus screenshots, to see where page-loading time goes.
 */
@RunWith(AndroidJUnit4::class)
class PageLoadPerfTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val target = inst.targetContext
    private val shots = File(target.getExternalFilesDir(null), "perf").apply { mkdirs() }

    private fun asset(name: String): Uri {
        val dir = File(target.cacheDir, "perf").apply { mkdirs() }
        val f = File(dir, name)
        inst.context.assets.open(name).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return Uri.fromFile(f)
    }

    private fun shot(label: String) {
        val b = inst.uiAutomation.takeScreenshot() ?: return
        val s = Bitmap.createScaledBitmap(b, b.width * 2 / 5, b.height * 2 / 5, true)
        File(shots, "$label.jpg").outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        Perf.log("shot $label")
    }

    private fun run(file: String) {
        val uri = asset(file)
        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(intent).use { sc ->
            fun scroll(p: Int) = sc.onActivity { a ->
                a.findViewById<RecyclerView>(R.id.recycler).scrollToPosition(p)
            }
            val tag = file.substringBefore('.')
            Perf.log("phase $tag open")
            Thread.sleep(300); shot("${tag}_1open_0300ms")
            Thread.sleep(700); shot("${tag}_1open_1000ms")
            Thread.sleep(4000); shot("${tag}_1open_5000ms")

            Perf.log("phase $tag scrub")
            for (p in 1..30) {
                scroll(p)
                Thread.sleep(50)
            }
            Perf.log("phase $tag scrub-stop")
            Thread.sleep(300); shot("${tag}_2far_0300ms")
            Thread.sleep(3700); shot("${tag}_2far_4000ms")

            Perf.log("phase $tag back")
            scroll(0)
            Thread.sleep(150); shot("${tag}_3back_0150ms")
            Thread.sleep(450); shot("${tag}_3back_0600ms")
            Thread.sleep(1400); shot("${tag}_3back_2000ms")

            Perf.log("phase $tag flown")
            scroll(15)
            Thread.sleep(150); shot("${tag}_4flown_0150ms")
            Thread.sleep(450); shot("${tag}_4flown_0600ms")
            Thread.sleep(1400); shot("${tag}_4flown_2000ms")
            Perf.log("phase $tag end")
        }
    }

    @Test
    fun pageLoading() {
        run("text.pdf")
        run("scan.pdf")
    }
}
