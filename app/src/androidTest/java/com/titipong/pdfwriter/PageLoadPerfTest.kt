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
        val dir = File(target.cacheDir, "perf").apply { mkdirs() }
        val f = File(dir, "grid_hl.pdf")
        if (f.exists()) {
            f.setWritable(true)
            f.delete()
        }
        inst.context.assets.open("text.pdf").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.setReadOnly()
        val uri = Uri.fromFile(f)

        // Pages 0..24 and 75..99: plain. Pages 25..74: dense S Pen highlighter strokes (60 pts each) + text highlights.
        val pages = HashMap<Int, PageAnnotations>()
        val hlYellow = Color.parseColor("#FACC15")
        val hlGreen = Color.parseColor("#4ADE80")
        val penBlue = Color.parseColor("#2563EB")
        for (p in 25 until 75) {
            val pa = PageAnnotations()
            for (i in 0 until 35) {
                val y = 0.06f + (i % 25) * 0.052f
                val pts = ArrayList<StrokePoint>(60)
                for (k in 0 until 60) {
                    val x = 0.08f + k * (0.82f / 59f)
                    val wy = y + kotlin.math.sin(k * 0.25f) * 0.0018f
                    pts.add(StrokePoint(x, wy, 0.5f))
                }
                pa.strokes.add(InkStroke(pts, if (i % 2 == 0) hlYellow else hlGreen, 0.025f, highlighter = true))
            }
            for (i in 0 until 20) {
                val y = 0.09f + (i % 20) * 0.062f
                pa.highlights.add(
                    TextHighlight(
                        listOf(
                            RectF(0.09f, y, 0.88f, y + 0.019f),
                            RectF(0.09f, y + 0.022f, 0.65f, y + 0.041f)
                        ),
                        hlYellow,
                        "highlight",
                        underline = false
                    )
                )
            }
            for (i in 0 until 6) {
                val y = 0.14f + i * 0.18f
                val pts = mutableListOf(StrokePoint(0.05f, y, 0.4f), StrokePoint(0.07f, y + 0.015f, 0.7f), StrokePoint(0.06f, y + 0.03f, 0.5f))
                pa.strokes.add(InkStroke(pts, penBlue, 0.004f, highlighter = false))
            }
            pages[p] = pa
        }
        val key = AnnotationStorage.keyFor(uri.toString())
        AnnotationStorage.save(
            target, key, AnnotationStorage.toJson(pages, emptyList(), synced = false), System.currentTimeMillis()
        )

        val ht = HandlerThread("frame-metrics").apply { start() }
        val h = Handler(ht.looper)
        class FrameSample(
            val total: Double,
            val anim: Double,
            val draw: Double,
            val sync: Double,
            val cmd: Double
        )
        val samples = Collections.synchronizedList(ArrayList<FrameSample>())
        val listener = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            val total = fm.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
            val anim = fm.getMetric(FrameMetrics.ANIMATION_DURATION) / 1_000_000.0
            val draw = fm.getMetric(FrameMetrics.DRAW_DURATION) / 1_000_000.0
            val sync = fm.getMetric(FrameMetrics.SYNC_DURATION) / 1_000_000.0
            val cmd = fm.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) / 1_000_000.0
            if (total > 0) samples.add(FrameSample(total, anim, draw, sync, cmd))
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { sc ->
                Thread.sleep(1200)
                // Visit a few highlighted pages in the reader first so full-res disk JPEGs also exist, like real usage.
                for (rp in intArrayOf(28, 30, 32)) {
                    sc.onActivity { a -> a.findViewById<RecyclerView>(R.id.recycler).scrollToPosition(rp) }
                    Thread.sleep(500)
                }
                shot("main_highlighted")
                sc.onActivity { a ->
                    a.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_pages, 0)
                    a.findViewById<RecyclerView>(R.id.pagesGrid).scrollToPosition(0)
                    a.window.addOnFrameMetricsAvailableListener(listener, h)
                }
                Thread.sleep(600)

                fun measureFastFling(label: String, down: Boolean) {
                    samples.clear()
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val t0 = System.nanoTime()
                    sc.onActivity { a ->
                        val rv = a.findViewById<RecyclerView>(R.id.pagesGrid)
                        val sign = if (down) 1 else -1
                        val v = (rv.maxFlingVelocity * 0.9f).toInt() * sign
                        rv.fling(0, v)
                        rv.postDelayed({ rv.fling(0, v) }, 420)
                        rv.postDelayed({ latch.countDown() }, 1250)
                    }
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    Thread.sleep(150)
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                    val snap = synchronized(samples) { samples.toList() }
                    if (snap.isEmpty()) {
                        Perf.log("grid_$label frames=0")
                    } else {
                        val totals = snap.map { it.total }.sorted()
                        val avg = totals.average()
                        val p95 = totals[((totals.size - 1) * 0.95).toInt()]
                        val max = totals.last()
                        val animAvg = snap.map { it.anim }.average()
                        val drawAvg = snap.map { it.draw }.average()
                        val syncAvg = snap.map { it.sync }.average()
                        val cmdAvg = snap.map { it.cmd }.average()
                        val jank16 = totals.count { it > 16.67 }
                        val jank33 = totals.count { it > 33.33 }
                        Perf.log(
                            String.format(
                                "grid_%s frames=%d avg=%.2fms p95=%.2fms max=%.2fms anim=%.2fms draw=%.2fms sync=%.2fms cmd=%.2fms jank16=%d jank33=%d elapsed=%.0fms",
                                label, snap.size, avg, p95, max, animAvg, drawAvg, syncAvg, cmdAvg, jank16, jank33, elapsedMs
                            )
                        )
                    }
                }

                // 1) Fast fling DOWN (0 -> 99) right through dense highlighted pages 25..74
                measureFastFling("down_cold", down = true)
                Thread.sleep(300)
                shot("allpages_bottom")
                // 2) Fast fling UP (99 -> 0) right back through dense highlighted pages 74..25
                measureFastFling("up_first", down = false)
                Thread.sleep(300)
                shot("allpages_top")

                // Visit highlighted rows briefly so PDF page images + highlights are fully composited in cache,
                // then benchmark warm fast flings DOWN and UP across those rendered highlighted thumbnails.
                for (hp in intArrayOf(28, 44, 60)) {
                    sc.onActivity { a -> a.findViewById<RecyclerView>(R.id.pagesGrid).scrollToPosition(hp) }
                    Thread.sleep(650)
                }
                sc.onActivity { a -> a.findViewById<RecyclerView>(R.id.pagesGrid).scrollToPosition(0) }
                Thread.sleep(400)

                // 3) Warm fast fling DOWN (0 -> 99) and UP (99 -> 0)
                measureFastFling("down_warm", down = true)
                measureFastFling("up_warm", down = false)

                // Settle on highlighted pages and capture screenshot to verify highlight visual fidelity.
                sc.onActivity { a ->
                    a.findViewById<RecyclerView>(R.id.pagesGrid).scrollToPosition(32)
                }
                Thread.sleep(900)
                shot("allpages_highlighted")
                sc.onActivity { a -> a.window.removeOnFrameMetricsAvailableListener(listener) }
            }
        } finally {
            ht.quitSafely()
        }
    }
}
