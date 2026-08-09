package com.scrtrans

/**
 * The colours the source text was drawn in, read off the screen by [ColorSampler].
 *
 * [bg] is the surface behind the text and [ink] the text itself. Null wherever the
 * sample was not trustworthy — text over a photo has no single surface colour — and the
 * caller then falls back to the old dark-on-white.
 */
data class SourceColors(val bg: Int, val ink: Int)
