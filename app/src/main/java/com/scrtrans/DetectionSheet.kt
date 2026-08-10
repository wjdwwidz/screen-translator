package com.scrtrans

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The offer that [JapaneseScout] makes when it finds a new Japanese app: a sheet at the
 * bottom of whatever the user is looking at.
 *
 * A status bar notification was the other option and is worse here. The finding is about
 * the app on screen right now, so the offer belongs on that screen — a tray entry asks
 * the user to leave, remember why, and come back.
 *
 * This is a second [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] window,
 * separate from the translation overlay because it is the one thing here that must take
 * touches. Same type for the same reason: no SYSTEM_ALERT_WINDOW permission, and the
 * window stays out of the accessibility tree, so the sheet's own Korean is not text we
 * then read back and try to translate.
 *
 * Tapping 번역 켜기 is the only thing that switches an app on. Everything else — the
 * timeout, 나중에, walking away — leaves it off, and the row stays in the list either way.
 */
object DetectionSheet {

    /** Long enough to read and decide, short enough not to sit on the app. */
    private const val AUTO_DISMISS_MS = 12_000L

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { dismiss() }

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var shownFor: String? = null

    fun show(service: AccessibilityService, pkg: String) {
        dismiss()

        val wm = service.getSystemService(WindowManager::class.java) ?: run {
            loge("no WindowManager for sheet")
            return
        }
        // An accessibility overlay is laid out against the whole display, so the sheet
        // would otherwise sit under the navigation bar with its buttons in the gesture
        // area. Read the inset off the window metrics rather than waiting for a dispatch
        // to the view — this window gets none reliably, and the buttons cannot be a
        // frame late.
        val bottomInset = wm.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            .bottom
        val sheet = build(service, pkg, bottomInset)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touchable, unlike the translation overlay — the buttons are the point.
            // Still not focusable, so the app underneath keeps its keyboard and cursor.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            wm.addView(sheet, params)
        } catch (e: Exception) {
            loge("sheet addView failed", e)
            return
        }
        windowManager = wm
        view = sheet
        shownFor = pkg
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
        logi("sheet shown for $pkg")
    }

    /**
     * Takes the sheet down once the user has moved on. Called on every collect pass, so
     * an offer about one app never ends up sitting over another.
     */
    fun dismissIfNotFor(pkg: String?) {
        if (view != null && pkg != shownFor) dismiss()
    }

    fun dismiss() {
        handler.removeCallbacks(autoDismiss)
        val v = view ?: return
        try {
            windowManager?.removeView(v)
        } catch (e: Exception) {
            logw("sheet removeView failed", e)
        }
        view = null
        windowManager = null
        shownFor = null
    }

    private fun build(context: Context, pkg: String, bottomInset: Int): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18) + bottomInset)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                // Rounded at the top only: the sheet is anchored to the bottom edge.
                cornerRadii = floatArrayOf(
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
            elevation = dp(12).toFloat()
        }

        card.addView(TextView(context).apply {
            text = "일본어 앱을 찾았습니다"
            textSize = 13f
            setTextColor(Color.rgb(140, 140, 148))
        })

        card.addView(TextView(context).apply {
            text = appLabel(context, pkg)
            textSize = 19f
            setTextColor(Color.rgb(24, 24, 28))
            setPadding(0, dp(4), 0, 0)
        })

        // Two apps from the same publisher can carry near-identical labels — HOT PEPPER
        // and ホットペッパー — so the package name is what actually tells them apart.
        card.addView(TextView(context).apply {
            text = pkg
            textSize = 11f
            setTextColor(Color.rgb(160, 160, 168))
        })

        card.addView(TextView(context).apply {
            text = "이 앱의 화면을 한국어로 겹쳐 보여드릴까요?"
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 96))
            setPadding(0, dp(10), 0, dp(14))
        })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        buttons.addView(Button(context).apply {
            text = "나중에"
            setTextColor(Color.rgb(90, 90, 96))
            background = GradientDrawable().apply {
                setColor(Color.rgb(238, 238, 242))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                logi("sheet declined for $pkg")
                dismiss()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        buttons.addView(View(context), LinearLayout.LayoutParams(dp(10), 1))

        buttons.addView(Button(context).apply {
            text = "번역 켜기"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 130, 70))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                TargetApps.setEnabled(pkg, true)
                dismiss()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        return card
    }
}

/** The app's own name where we can get it, the package name where we cannot. */
fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: PackageManager.NameNotFoundException) {
    pkg
}
