package com.titipong.pdfwriter.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * LinearLayoutManager that tells each page row how tall the screen is (vertical scrolling), so a
 * whole page fits. Passing it in the measure spec (not reading the list's height later) means rows
 * are measured again whenever the screen height changes — otherwise rows measured during the first
 * layout, before the list had its height, stayed full-width while others fit the screen.
 */
class PageLayoutManager(context: Context, orientation: Int) :
    LinearLayoutManager(context, orientation, false) {

    override fun measureChildWithMargins(child: View, widthUsed: Int, heightUsed: Int) {
        if (orientation != VERTICAL) {
            super.measureChildWithMargins(child, widthUsed, heightUsed)
            return
        }
        val lp = child.layoutParams as RecyclerView.LayoutParams
        val wSpec = getChildMeasureSpec(
            width, widthMode,
            paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed,
            lp.width, canScrollHorizontally()
        )
        // Only the top padding: the list adds bottom padding while zoomed, which mustn't resize pages.
        val room = (height - paddingTop - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)
        child.measure(wSpec, View.MeasureSpec.makeMeasureSpec(room, View.MeasureSpec.UNSPECIFIED))
    }
}
