package com.titipong.pdfwriter.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Long-press and drag horizontal reordering for a row of color swatches.
 * Short tap keeps the swatch's normal click behavior (select, or edit when already selected).
 */
object SwatchReorder {

    /**
     * Moves item at [from] to [to] in [list] and returns the updated [selected] index so the
     * currently selected color stays selected even if its slot shifted.
     */
    fun move(list: MutableList<Int>, selected: Int, from: Int, to: Int): Int {
        if (from !in list.indices || to !in list.indices || from == to) return selected
        val item = list.removeAt(from)
        list.add(to, item)
        return when {
            selected == from -> to
            from < to && selected in (from + 1)..to -> selected - 1
            from > to && selected in to until from -> selected + 1
            else -> selected
        }
    }

    /** Cancels any settle animations and clears temporary drag transforms on [views]. */
    fun reset(views: List<View>) {
        for (v in views) {
            v.animate().cancel()
            v.translationX = 0f
            v.translationZ = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.isPressed = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        row: LinearLayout,
        views: List<View>,
        count: () -> Int,
        onTap: (Int) -> Unit,
        onMove: (from: Int, to: Int) -> Unit
    ) {
        row.clipChildren = false
        row.clipToPadding = false
        val ctx = row.context
        val density = ctx.resources.displayMetrics.density
        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

        var activePointerId = MotionEvent.INVALID_POINTER_ID
        var activeIndex = -1
        var dragging = false
        var fromIndex = -1
        var toIndex = -1
        var downRawX = 0f
        var downRawY = 0f
        var dragStartRawX = 0f
        var lastRawX: Float
        var movedPastSlop = false
        var slotLefts = FloatArray(0)
        var slotCenters = FloatArray(0)
        var pendingLongPress: Runnable? = null

        fun cancelLongPress(v: View) {
            pendingLongPress?.let { v.removeCallbacks(it) }
            pendingLongPress = null
        }

        fun rawXOf(ev: MotionEvent, ptrIdx: Int): Float =
            if (ptrIdx == 0) ev.rawX else ev.rawX + (ev.getX(ptrIdx) - ev.getX(0))

        fun rawYOf(ev: MotionEvent, ptrIdx: Int): Float =
            if (ptrIdx == 0) ev.rawY else ev.rawY + (ev.getY(ptrIdx) - ev.getY(0))

        views.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (i < count()) onTap(i)
            }
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (activePointerId != MotionEvent.INVALID_POINTER_ID || i >= count()) return@setOnTouchListener false
                        activePointerId = ev.getPointerId(0)
                        activeIndex = i
                        dragging = false
                        movedPastSlop = false
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        lastRawX = ev.rawX
                        view.isPressed = true
                        val lp = Runnable {
                            pendingLongPress = null
                            if (activeIndex != i || movedPastSlop || dragging) return@Runnable
                            if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) return@Runnable
                            val n = count().coerceAtMost(views.size)
                            if (n <= 1 || i >= n) return@Runnable
                            dragging = true
                            fromIndex = i
                            toIndex = i
                            dragStartRawX = lastRawX
                            slotLefts = FloatArray(n) { views[it].left.toFloat() }
                            slotCenters = FloatArray(n) { views[it].left + views[it].width / 2f }
                            (row.parent as? ViewGroup)?.clipChildren = false
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            row.parent?.requestDisallowInterceptTouchEvent(true)
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            view.animate().cancel()
                            view.translationZ = 8f * density
                            view.animate().scaleX(1.16f).scaleY(1.16f).setDuration(100).start()
                        }
                        pendingLongPress = lp
                        view.postDelayed(lp, longPressMs)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (activeIndex != i || activePointerId == MotionEvent.INVALID_POINTER_ID) return@setOnTouchListener false
                        val ptrIdx = ev.findPointerIndex(activePointerId)
                        if (ptrIdx < 0) return@setOnTouchListener true
                        val rx = rawXOf(ev, ptrIdx)
                        val ry = rawYOf(ev, ptrIdx)
                        lastRawX = rx
                        if (!dragging) {
                            if (!movedPastSlop && (abs(rx - downRawX) > touchSlop || abs(ry - downRawY) > touchSlop)) {
                                movedPastSlop = true
                                view.isPressed = false
                                cancelLongPress(view)
                            }
                            return@setOnTouchListener true
                        }
                        val minLeft = slotLefts.minOrNull() ?: 0f
                        val maxLeft = slotLefts.maxOrNull() ?: 0f
                        val pad = 6f * density
                        val dx = (rx - dragStartRawX).coerceIn(
                            minLeft - slotLefts[fromIndex] - pad,
                            maxLeft - slotLefts[fromIndex] + pad
                        )
                        view.translationX = dx
                        val dragCenter = slotCenters[fromIndex] + dx
                        var nearest = toIndex
                        var bestDist = Float.MAX_VALUE
                        for (k in slotCenters.indices) {
                            val d = abs(slotCenters[k] - dragCenter)
                            if (d < bestDist) {
                                bestDist = d
                                nearest = k
                            }
                        }
                        if (nearest != toIndex) {
                            toIndex = nearest
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            for (k in slotLefts.indices) {
                                if (k == fromIndex) continue
                                val targetTx = when {
                                    fromIndex < toIndex && k in (fromIndex + 1)..toIndex -> slotLefts[k - 1] - slotLefts[k]
                                    fromIndex > toIndex && k in toIndex until fromIndex -> slotLefts[k + 1] - slotLefts[k]
                                    else -> 0f
                                }
                                views[k].animate().translationX(targetTx).setDuration(120).start()
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        if (activeIndex != i || ev.getPointerId(ev.actionIndex) != activePointerId) {
                            return@setOnTouchListener true
                        }
                        finishGesture(
                            view, views, density, cancelled = false,
                            dragging = dragging, movedPastSlop = movedPastSlop,
                            from = fromIndex, to = toIndex, n = slotLefts.size,
                            cancelLp = { cancelLongPress(view) },
                            clearState = {
                                dragging = false
                                activePointerId = MotionEvent.INVALID_POINTER_ID
                                activeIndex = -1
                                fromIndex = -1
                                toIndex = -1
                            },
                            onMove = onMove
                        )
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (activeIndex != i) return@setOnTouchListener false
                        finishGesture(
                            view, views, density, cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL,
                            dragging = dragging, movedPastSlop = movedPastSlop,
                            from = fromIndex, to = toIndex, n = slotLefts.size,
                            cancelLp = { cancelLongPress(view) },
                            clearState = {
                                dragging = false
                                activePointerId = MotionEvent.INVALID_POINTER_ID
                                activeIndex = -1
                                fromIndex = -1
                                toIndex = -1
                            },
                            onMove = onMove
                        )
                        true
                    }

                    else -> false
                }
            }
        }
    }

    private fun finishGesture(
        view: View,
        views: List<View>,
        density: Float,
        cancelled: Boolean,
        dragging: Boolean,
        movedPastSlop: Boolean,
        from: Int,
        to: Int,
        n: Int,
        cancelLp: () -> Unit,
        clearState: () -> Unit,
        onMove: (from: Int, to: Int) -> Unit
    ) {
        cancelLp()
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
        if (!dragging) {
            clearState()
            if (!cancelled && !movedPastSlop) {
                view.performClick()
            }
            return
        }
        val count = n.coerceAtMost(views.size)
        val visualLefts = FloatArray(count) { views[it].left + views[it].translationX }
        val dragScale = view.scaleX
        clearState()
        if (!cancelled && from in 0 until count && to in 0 until count && from != to) {
            onMove(from, to)
            for (newIdx in 0 until count) {
                val oldIdx = when {
                    newIdx == to -> from
                    from < to && newIdx in from until to -> newIdx + 1
                    from > to && newIdx in (to + 1)..from -> newIdx - 1
                    else -> newIdx
                }
                val child = views[newIdx]
                child.animate().cancel()
                child.translationX = visualLefts[oldIdx] - child.left
                if (newIdx == to) {
                    child.scaleX = dragScale
                    child.scaleY = dragScale
                    child.translationZ = 8f * density
                    child.animate()
                        .translationX(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(0f)
                        .setDuration(110)
                        .start()
                } else {
                    child.scaleX = 1f
                    child.scaleY = 1f
                    child.translationZ = 0f
                    if (abs(child.translationX) > 0.5f) {
                        child.animate().translationX(0f).setDuration(110).start()
                    } else {
                        child.translationX = 0f
                    }
                }
            }
        } else {
            for (k in 0 until count) {
                val child = views[k]
                child.animate().cancel()
                child.animate()
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(110)
                    .start()
            }
        }
    }
}
