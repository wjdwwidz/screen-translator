package com.scrtrans

import android.graphics.Rect

/**
 * One on-screen string with its absolute screen bounds, as read from the
 * accessibility tree. [bounds] comes straight from getBoundsInScreen() and is
 * therefore in display coordinates, not overlay-window coordinates — OverlayView
 * does the conversion at draw time.
 *
 * [inkLines] and [sourceLineHeight] come from the character-location API and describe
 * where the Japanese actually sits, which is often nowhere near the node box: a
 * button's label is centred, and a drawableStart icon pushes the text right. Both are
 * absent for text the API cannot describe (EditText hints), in which case OverlayView
 * falls back to estimating from the box.
 *
 * [container] is the nearest ancestor box wider than this node — the space the text was
 * laid out in. It is what tells centred text apart from left-aligned text: a bottom-tab
 * label is a wrap_content TextView, so its own box says nothing, but sitting with equal
 * slack inside the tab cell does.
 */
data class TextItem(
    val text: String,
    val bounds: Rect,
    val inkLines: List<Rect> = emptyList(),
    val sourceLineHeight: Float = 0f,
    val container: Rect = bounds,
    /** Filled in by the settle pass's screenshot; null where it was not trustworthy. */
    val colors: SourceColors? = null,
) {
    val hasInk get() = inkLines.isNotEmpty() && sourceLineHeight > 0f
}
