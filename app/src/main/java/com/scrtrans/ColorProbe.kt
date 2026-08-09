package com.scrtrans

import android.text.Spanned
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Investigation, concluded: no node carries its colours, so the pixels are the only
 * route and [ColorSampler] is what took it.
 *
 * Two candidates were ruled out on the device, and this is what ruled them out — keep
 * it for re-checking on a new app or a new OS version:
 *  1. extraRenderingInfo — null on every node, and it carries no colour field anyway.
 *  2. Colour spans. ForegroundColorSpan is a ParcelableSpan and would survive the IPC,
 *     but node.text comes back a plain String, never a Spanned, so there is nothing to
 *     read even for text the app did style by span. node.extras is empty too.
 *
 * Flip [ENABLED] to log both per node.
 */
object ColorProbe {

    const val ENABLED = false

    fun inspectNode(node: AccessibilityNodeInfo, text: String) {
        val eri = node.extraRenderingInfo
        val eriDesc = if (eri == null) "null" else
            "textSizeInPx=${eri.textSizeInPx} unit=${eri.textSizeUnit} layout=${eri.layoutSize}"

        val cs = node.text
        val spanDesc = if (cs is Spanned) {
            val spans = cs.getSpans(0, cs.length, Any::class.java)
            if (spans.isEmpty()) "Spanned, no spans"
            else spans.joinToString(", ") { it.javaClass.simpleName }
        } else {
            cs?.javaClass?.simpleName ?: "null"
        }

        val extraKeys = node.extras?.keySet()?.joinToString(",") ?: "-"

        logi("COLORPROBE \"$text\" eri=$eriDesc text=$spanDesc extras=[$extraKeys]")
    }
}
