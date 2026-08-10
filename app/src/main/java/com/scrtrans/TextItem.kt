package com.scrtrans

import android.graphics.Rect

/**
 * One on-screen string with its absolute screen bounds, as read from the
 * accessibility tree. [bounds] comes straight from getBoundsInScreen() and is
 * therefore in display coordinates, not overlay-window coordinates — OverlayView
 * does the conversion at draw time.
 *
 * [inkLines], [sourceLineHeight] and [sourceEmSize] come from the character-location API
 * and describe where the Japanese actually sits and how big it is, which is often nowhere
 * near what the node box suggests: a button's label is centred, and a drawableStart icon
 * pushes the text right. They are absent for text the API cannot describe (EditText
 * hints), in which case OverlayView falls back to estimating from the box.
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
    /** The source's font size in px, or 0 where its glyphs could not be measured. */
    val sourceEmSize: Float = 0f,
    val container: Rect = bounds,
    /** Filled in by the settle pass's screenshot; null where it was not trustworthy. */
    val colors: SourceColors? = null,
    /**
     * Where the node box's content ends, read off the same screenshot; 0 if unread.
     * Only collected for boxes with no ink, which are the only ones that need it — see
     * ColorSampler.contentRight.
     */
    val inkRight: Int = 0,
) {
    val hasInk get() = inkLines.isNotEmpty() && sourceLineHeight > 0f
}
