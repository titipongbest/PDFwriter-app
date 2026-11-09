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
            // Horizontal paging: a page is always one screen wide. The list adds right padding
            // while zoomed (to reach the last page); it must not shrink the pages, or zooming a
            // double-page spread squeezed and shifted it.
            val lp = child.layoutParams as RecyclerView.LayoutParams
            val w = (width - paddingLeft - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
            val hSpec = getChildMeasureSpec(
                height, heightMode,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
                lp.height, canScrollVertically()
            )
            child.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), hSpec)
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
