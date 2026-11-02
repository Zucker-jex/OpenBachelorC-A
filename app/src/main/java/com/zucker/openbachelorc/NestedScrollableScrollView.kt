package com.zucker.openbachelorc

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * A ScrollView that keeps vertical drags for itself.
 *
 * The log panel sits inside the page's [androidx.core.widget.NestedScrollView].
 * A plain ScrollView there never scrolls: the parent claims the vertical drag on
 * the first move event, so the gesture scrolls the page instead of the log.
 * Requesting `disallowInterceptTouchEvent` while this view can still scroll in
 * the direction of the drag keeps the parent out of the way, and handing the
 * gesture back once we hit an edge restores normal page scrolling.
 */
class NestedScrollableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    private var lastY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastY = ev.y
            MotionEvent.ACTION_MOVE -> {
                val dy = lastY - ev.y
                lastY = ev.y
                if (dy > 0 && !canScrollDown() || dy < 0 && !canScrollUp()) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun canScrollUp(): Boolean = scrollY > 0

    private fun canScrollDown(): Boolean {
        val child = getChildAt(0) ?: return false
        return scrollY < child.measuredHeight - height
    }
}
