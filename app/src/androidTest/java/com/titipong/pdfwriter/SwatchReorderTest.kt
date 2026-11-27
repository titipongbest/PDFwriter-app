package com.titipong.pdfwriter

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.titipong.pdfwriter.ui.SwatchReorder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwatchReorderTest {

    @Test
    fun moveUpdatesListAndSelectedIndex() {
        // Drag selected item right: [0, 1*, 2, 3, 4] -> [0, 2, 3, 1*, 4]
        val a = mutableListOf(10, 20, 30, 40, 50)
        assertEquals(3, SwatchReorder.move(a, selected = 1, from = 1, to = 3))
        assertEquals(listOf(10, 30, 40, 20, 50), a)

        // Drag unselected item from left of selected to right of selected: selected shifts left
        val b = mutableListOf(10, 20, 30, 40, 50)
        assertEquals(1, SwatchReorder.move(b, selected = 2, from = 0, to = 3))
        assertEquals(listOf(20, 30, 40, 10, 50), b)

        // Drag unselected item from right of selected to left of selected: selected shifts right
        val c = mutableListOf(10, 20, 30, 40, 50)
        assertEquals(3, SwatchReorder.move(c, selected = 2, from = 4, to = 1))
        assertEquals(listOf(10, 50, 20, 30, 40), c)

        // Drag outside selected range: selected unchanged
        val d = mutableListOf(10, 20, 30, 40, 50)
        assertEquals(0, SwatchReorder.move(d, selected = 0, from = 2, to = 4))
        assertEquals(listOf(10, 20, 40, 50, 30), d)
    }

    @Test
    fun longPressDragReordersPenAndHighlighterInBothModes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("pdfwriter", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val lpDelay = ViewConfiguration.getLongPressTimeout().toLong() + 120L

            fun dragSwatch(rowId: Int, from: Int, to: Int) {
                var fromView: View? = null
                var dx = 0f
                var cx = 0f
                var cy = 0f
                sc.onActivity { a ->
                    val row = a.findViewById<LinearLayout>(rowId)
                    val vFrom = row.getChildAt(from)
                    val vTo = row.getChildAt(to)
                    val locFrom = IntArray(2).also { vFrom.getLocationOnScreen(it) }
                    val locTo = IntArray(2).also { vTo.getLocationOnScreen(it) }
                    fromView = vFrom
                    cx = vFrom.width / 2f
                    cy = vFrom.height / 2f
                    dx = (locTo[0] + vTo.width / 2f) - (locFrom[0] + vFrom.width / 2f)
                }
                val downTime = SystemClock.uptimeMillis()
                sc.onActivity {
                    val ev = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
                    fromView!!.dispatchTouchEvent(ev)
                    ev.recycle()
                }
                Thread.sleep(lpDelay)
                val steps = 6
                for (s in 1..steps) {
                    val x = cx + dx * s / steps
                    val now = SystemClock.uptimeMillis()
                    sc.onActivity {
                        val ev = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x, cy, 0)
                        fromView!!.dispatchTouchEvent(ev)
                        ev.recycle()
                    }
                    Thread.sleep(20)
                }
                val upTime = SystemClock.uptimeMillis()
                sc.onActivity {
                    val ev = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, cx + dx, cy, 0)
                    fromView!!.dispatchTouchEvent(ev)
                    ev.recycle()
                }
                Thread.sleep(150)
            }

            // 1) Annotate mode - Pen: drag swatch 0 to 3
            val penBefore = prefs.getString("tool_PEN_palette", null)!!.split(',').map { it.toInt() }
            dragSwatch(R.id.colorRow, from = 0, to = 3)
            val penAfter = prefs.getString("tool_PEN_palette", null)!!.split(',').map { it.toInt() }
            val penExpected = penBefore.toMutableList().also { SwatchReorder.move(it, 1, 0, 3) }
            assertEquals(penExpected, penAfter)

            // 2) Annotate mode - Highlighter: switch tool, drag swatch 4 to 1
            sc.onActivity { a -> a.findViewById<View>(R.id.btnHighlight).performClick() }
            Thread.sleep(100)
            val hlBefore = prefs.getString("tool_HIGHLIGHTER_palette", null)!!.split(',').map { it.toInt() }
            dragSwatch(R.id.colorRow, from = 4, to = 1)
            val hlAfter = prefs.getString("tool_HIGHLIGHTER_palette", null)!!.split(',').map { it.toInt() }
            val hlExpected = hlBefore.toMutableList().also { SwatchReorder.move(it, 0, 4, 1) }
            assertEquals(hlExpected, hlAfter)

            // 3) View mode - Highlight colors: switch to View mode, drag swatch 0 to 4
            prefs.edit().putBoolean("interaction_view", true).commit()
            sc.recreate()
            Thread.sleep(200)
            val viewBefore = prefs.getString("highlight_palette", null)?.split(',')?.map { it.toInt() }
            dragSwatch(R.id.hlColorRow, from = 0, to = 4)
            val viewAfter = prefs.getString("highlight_palette", null)!!.split(',').map { it.toInt() }
            if (viewBefore != null) {
                val viewExpected = viewBefore.toMutableList().also { SwatchReorder.move(it, 0, 0, 4) }
                assertEquals(viewExpected, viewAfter)
            } else {
                // Default selected index 0 was dragged to 4
                assertEquals(4, prefs.getInt("highlight_color", -1))
            }
        }
    }
}
