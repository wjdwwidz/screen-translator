package com.scrtrans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Draws translation boxes straight onto a Canvas. No child views, no layout pass:
 * every item already carries absolute screen coordinates, so the layout system
 * would have nothing to compute.
 */
class OverlayView(context: Context) : View(context) {

    private var items: List<TextItem> = emptyList()

    private val originOnScreen = IntArray(2)

    private val debugFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 255, 0, 0)
    }

    fun setItems(newItems: List<TextItem>) {
        items = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        // Item bounds are absolute screen coordinates, but this window starts
        // below the status bar. Measure where we actually are and shift the
        // canvas up by that much, so the two coordinate spaces line up on any
        // device or OS version.
        getLocationOnScreen(originOnScreen)
        canvas.save()
        canvas.translate(-originOnScreen[0].toFloat(), -originOnScreen[1].toFloat())

        for (item in items) {
            canvas.drawRect(item.bounds, debugFill)
        }

        canvas.restore()
    }
}
