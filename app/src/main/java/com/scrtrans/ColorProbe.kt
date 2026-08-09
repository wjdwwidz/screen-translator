package com.scrtrans

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.text.Spanned
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Investigation, not yet concluded: is there any route to the source's colours?
 *
 * We draw dark-on-white everywhere, so a label that was white-on-pink (the 検索 button)
 * becomes a white patch inside a pink button. The accessibility tree carries no colour,
 * and reading pixels was assumed to need MediaProjection, which parks a capture icon in
 * the status bar for as long as it runs.
 *
 * Three candidates, none yet confirmed or ruled out on a device:
 *  1. extraRenderingInfo — reported null before, but so was the character-location API
 *     assumed impossible, and that turned out to work. Worth re-checking.
 *  2. Colour spans on the node's text. TextUtils preserves ParcelableSpans across the
 *     IPC and ForegroundColorSpan is one, so text styled by span rather than by
 *     setTextColor would come through.
 *  3. AccessibilityService.takeScreenshot, added in API 30. Unlike MediaProjection it
 *     is a privilege of the service itself. Open questions: does it show a capture
 *     indicator, does it see our own overlay (it likely does, which would need the
 *     overlay cleared for the frame), and the one-per-second rate limit.
 *
 * Flip [ENABLED] to log all three. Findings belong in the README, not here.
 */
object ColorProbe {

    const val ENABLED = false

    /** Candidates 1 and 2: anything useful hiding on the node itself? */
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

    /**
     * Candidate 3: AccessibilityService.takeScreenshot, added in API 30. Unlike
     * MediaProjection it is a privilege of the service itself, so the question is
     * whether it works here, how fast it is, whether it shows a capture indicator,
     * and whether it sees our own overlay.
     */
    fun captureAndSample(
        service: AccessibilityService,
        samples: List<Pair<String, Rect>>,
    ) {
        val t0 = System.nanoTime()
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            { it.run() },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val ms = (System.nanoTime() - t0) / 1_000_000.0
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bmp == null) {
                        logi("COLORPROBE screenshot: wrap failed")
                        return
                    }
                    logi("COLORPROBE screenshot ok ${bmp.width}x${bmp.height} (${"%.0f".format(ms)}ms)")
                    for ((label, r) in samples) {
                        val cx = ((r.left + r.right) / 2).coerceIn(0, bmp.width - 1)
                        val cy = ((r.top + r.bottom) / 2).coerceIn(0, bmp.height - 1)
                        // Just outside the ink is the surrounding surface.
                        val ox = (r.left - 12).coerceIn(0, bmp.width - 1)
                        val ink = bmp.getPixel(cx, cy)
                        val around = bmp.getPixel(ox, cy)
                        logi(
                            "COLORPROBE \"$label\" at($cx,$cy) ink=${hex(ink)} outside=${hex(around)}"
                        )
                    }
                    bmp.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    logi("COLORPROBE screenshot FAILED code=$errorCode")
                }
            },
        )
    }

    private fun hex(c: Int) = "#%02X%02X%02X".format((c shr 16) and 255, (c shr 8) and 255, c and 255)
}
