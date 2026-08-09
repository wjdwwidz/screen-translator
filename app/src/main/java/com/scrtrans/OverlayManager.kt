package com.scrtrans

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.WindowManager

/** Owns the overlay window's lifecycle. */
class OverlayManager(private val service: AccessibilityService) {

    private var windowManager: WindowManager? = null
    private var view: OverlayView? = null

    fun attach() {
        if (view != null) return

        val wm = service.getSystemService(WindowManager::class.java) ?: run {
            loge("no WindowManager")
            return
        }
        val v = OverlayView(service)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // TYPE_ACCESSIBILITY_OVERLAY, not TYPE_APPLICATION_OVERLAY: it needs no
            // SYSTEM_ALERT_WINDOW permission, and it is excluded from the accessibility
            // tree — otherwise we would read back our own Korean text and loop forever.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        try {
            wm.addView(v, params)
        } catch (e: Exception) {
            loge("addView failed", e)
            return
        }
        windowManager = wm
        view = v
        logi("overlay attached")
    }

    fun show(items: List<RenderItem>) {
        view?.setItems(items)
    }

    fun clear() {
        view?.setItems(emptyList())
    }

    fun detach() {
        val v = view ?: return
        try {
            windowManager?.removeView(v)
        } catch (e: Exception) {
            logw("removeView failed", e)
        }
        view = null
        windowManager = null
        logi("overlay detached")
    }
}
