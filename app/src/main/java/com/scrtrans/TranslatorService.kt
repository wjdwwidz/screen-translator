package com.scrtrans

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent

class TranslatorService : AccessibilityService() {

    companion object {
        const val TARGET_PACKAGE = "jp.hotpepper.android.beauty.hair"

        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var overlay: OverlayManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayManager(this)
        overlay.attach()
        running = true
        logi("service connected, target=$TARGET_PACKAGE")

        // A fixed red box at absolute screen coords (100,400)-(600,600). If the canvas
        // correction works, its top edge sits exactly 400px from the physical top of
        // the display, not 400px below the status bar.
        overlay.show(listOf(TextItem("probe", Rect(100, 400, 600, 600))))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reading the tree comes next.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        overlay.detach()
        logi("service unbound")
        return super.onUnbind(intent)
    }
}
