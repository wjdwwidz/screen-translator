package com.scrtrans

import android.graphics.Rect

/**
 * One on-screen string with its absolute screen bounds, as read from the
 * accessibility tree. [bounds] comes straight from getBoundsInScreen() and is
 * therefore in display coordinates, not overlay-window coordinates — OverlayView
 * does the conversion at draw time.
 */
data class TextItem(
    val text: String,
    val bounds: Rect,
)
