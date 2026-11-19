package com.titipong.pdfwriter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextHighlight
import com.titipong.pdfwriter.storage.AnnotationStorage
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

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

    @Test
    fun allPagesHighlightScroll() {
        val uri = asset("text.pdf")
        // Put dense highlighter strokes and text highlights on pages 10..49.
        val pages = HashMap<Int, PageAnnotations>()
        val hlYellow = Color.parseColor("#FACC15")
        val hlGreen = Color.parseColor("#4ADE80")
        for (p in 10 until 50) {
            val pa = PageAnnotations()
            for (i in 0 until 25) {
                val y = 0.08f + (i % 20) * 0.055f
                val pts = mutableListOf(
                    StrokePoint(0.08f, y, 0.5f),
                    StrokePoint(0.45f, y + 0.002f, 0.5f),
                    StrokePoint(0.88f, y, 0.5f)
                )
                pa.strokes.add(InkStroke(pts, if (i % 2 == 0) hlYellow else hlGreen, 0.025f, highlighter = true))
            }
            for (i in 0 until 15) {
                val y = 0.10f + (i % 15) * 0.07f
                pa.highlights.add(
                    TextHighlight(listOf(RectF(0.10f, y, 0.85f, y + 0.022f)), hlYellow, "highlight", underline = false)
                )
            }
            pages[p] = pa
        }
        val key = AnnotationStorage.keyFor(uri.toString())
        AnnotationStorage.save(
            target, key, AnnotationStorage.toJson(pages, emptyList(), synced = false), System.currentTimeMillis()
        )

        val ht = HandlerThread("frame-metrics").apply { start() }
        val h = Handler(ht.looper)
        val durationsMs = Collections.synchronizedList(ArrayList<Double>())
        val listener = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            val ns = fm.getMetric(FrameMetrics.TOTAL_DURATION)
            if (ns > 0) durationsMs.add(ns / 1_000_000.0)
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { sc ->
                Thread.sleep(1500)
                sc.onActivity { a ->
                    a.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_pages, 0)
                    a.window.addOnFrameMetricsAvailableListener(listener, h)
                }
                Thread.sleep(1500)

                fun measureScroll(label: String, steps: Int, dy: Int) {
                    durationsMs.clear()
                    val t0 = System.nanoTime()
                    repeat(steps) {
                        sc.onActivity { a ->
                            a.findViewById<RecyclerView>(R.id.pagesGrid).scrollBy(0, dy)
                        }
                        Thread.sleep(16)
                    }
                    Thread.sleep(250)
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                    val snap = synchronized(durationsMs) { durationsMs.sorted() }
                    if (snap.isEmpty()) {
                        Perf.log("grid_$label frames=0")
                    } else {
                        val avg = snap.average()
                        val p95 = snap[((snap.size - 1) * 0.95).toInt()]
                        val max = snap.last()
                        val jank16 = snap.count { it > 16.67 }
                        val jank33 = snap.count { it > 33.33 }
                        Perf.log(
                            String.format(
                                "grid_%s frames=%d avg=%.2fms p95=%.2fms max=%.2fms jank16=%d jank33=%d elapsed=%.0fms",
                                label, snap.size, avg, p95, max, jank16, jank33, elapsedMs
                            )
                        )
                    }
                }

                // 1) Scroll through plain pages (0..9)
                measureScroll("plain", steps = 25, dy = 45)
                // 2) Scroll into & through highlighted pages (10..49)
                measureScroll("highlighted", steps = 110, dy = 60)
                shot("allpages_highlighted")
                sc.onActivity { a -> a.window.removeOnFrameMetricsAvailableListener(listener) }
            }
        } finally {
            ht.quitSafely()
        }
    }
}
