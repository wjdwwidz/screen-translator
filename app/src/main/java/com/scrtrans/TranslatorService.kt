package com.scrtrans

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TranslatorService : AccessibilityService() {

    companion object {
        const val TARGET_PACKAGE = "jp.hotpepper.android.beauty.hair"

        /** Without this the tree gets re-walked dozens of times a second while scrolling. */
        private const val DEBOUNCE_MS = 300L

        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var overlay: OverlayManager
    private val handler = Handler(Looper.getMainLooper())
    private val collectTask = Runnable { collect() }

    private var screenW = 0
    private var screenH = 0
    private var lastWasTarget = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayManager(this)
        overlay.attach()
        running = true

        val m = resources.displayMetrics
        screenW = m.widthPixels
        screenH = m.heightPixels
        logi("service connected, target=$TARGET_PACKAGE screen=${screenW}x$screenH")
    }

    // Events arrive for every app, on purpose. Filtering by packageNames in the XML
    // would hide the moment we leave the target app, and the last screen's boxes
    // would sit there over whatever came next.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(collectTask)
        handler.postDelayed(collectTask, DEBOUNCE_MS)
    }

    private fun collect() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()

        if (root == null || pkg != TARGET_PACKAGE) {
            if (lastWasTarget) {
                overlay.clear()
                lastWasTarget = false
                logi("left target (now=$pkg), overlay cleared")
            }
            return
        }
        lastWasTarget = true

        val items = TextCollector.collect(root, screenW, screenH)
        logi("collected ${items.size} items")
        // No engine yet: redraw the source where it already is, which is what proves
        // the geometry. Adding translation first would mix up the causes of any error.
        overlay.show(items.map { RenderItem(it.text, it.text, it.bounds, translated = false) })
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        handler.removeCallbacks(collectTask)
        overlay.detach()
        logi("service unbound")
        return super.onUnbind(intent)
    }
}
