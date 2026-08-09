package com.scrtrans

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TranslatorService : AccessibilityService() {

    companion object {
        /** Without this the tree gets re-walked dozens of times a second while scrolling. */
        private const val DEBOUNCE_MS = 300L

        /**
         * Asking the app where each character sits costs ~450ms for a screen of unseen
         * strings, so the debounced pass never does it. This second pass, which only
         * runs once events stop, fills in what the cache could not answer.
         */
        private const val SETTLE_MS = 450L

        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var overlay: OverlayManager
    private lateinit var translator: TextTranslator

    private val handler = Handler(Looper.getMainLooper())
    private val collectTask = Runnable { collect(probe = false) }
    private val settleTask = Runnable { collect(probe = true) }

    private var screenW = 0
    private var screenH = 0
    private var lastWasTarget = false
    private var lastItems: List<TextItem> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayManager(this)
        overlay.attach()
        TranslationLog.init(this)
        TargetApps.init(this)

        // Cache/dedup on the outside, glossary short-circuit next, engine at the bottom.
        translator = CachingTranslator(GlossaryEngine(MlKitEngine())) {
            handler.post { render() }
        }
        translator.warmUp { ok ->
            logi("translator ready=$ok (glossary ${Glossary.verifiedCount} verified + ${Glossary.guessedCount} guessed)")
            render()
        }

        running = true
        val m = resources.displayMetrics
        screenW = m.widthPixels
        screenH = m.heightPixels
        logi("service connected, targets=${TargetApps.targets().joinToString(",")} screen=${screenW}x$screenH")
    }

    // Events arrive for every app, on purpose. Filtering by packageNames in the XML
    // would hide the moment we leave the target app, and the last screen's boxes
    // would sit there over whatever came next.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(collectTask)
        handler.removeCallbacks(settleTask)
        handler.postDelayed(collectTask, DEBOUNCE_MS)
    }

    private fun collect(probe: Boolean) {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()

        if (root == null || pkg !in TargetApps.targets()) {
            if (lastWasTarget) {
                lastItems = emptyList()
                overlay.clear()
                lastWasTarget = false
                logi("left target (now=$pkg), overlay cleared")
            }
            // Nothing is drawn here, but this is the only place that ever sees the other
            // apps on the device, so it is where a new Japanese one can be noticed.
            if (root != null && pkg != null) JapaneseScout.observe(this, pkg, root)
            return
        }
        lastWasTarget = true

        val t0 = System.nanoTime()
        lastItems = TextCollector.collect(root, screenW, screenH, probe)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val located = lastItems.count { it.hasInk }
        logi("collected ${lastItems.size} items, $located located, probe=$probe (${"%.0f".format(ms)}ms)")
        render()

        if (probe && ColorProbe.ENABLED) {
            ColorProbe.captureAndSample(
                this,
                lastItems.filter { it.hasInk }.take(6).map { it.text to it.inkLines.first() },
            )
        }

        // Anything still unlocated is either waiting on the settle pass or is text the
        // API will not describe. Either way, one more attempt once the screen is quiet.
        if (!probe && located < lastItems.size) {
            handler.removeCallbacks(settleTask)
            handler.postDelayed(settleTask, SETTLE_MS)
        }
    }

    /** Re-maps the current items through the translator. Cheap: it is a cache lookup. */
    private fun render() {
        if (!lastWasTarget) return
        overlay.show(
            lastItems.map { item ->
                val ko = translator.translateOrNull(item.text)
                RenderItem(
                    ko ?: item.text, item.bounds, ko != null,
                    item.inkLines, item.sourceLineHeight,
                )
            }
        )
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        handler.removeCallbacks(collectTask)
        handler.removeCallbacks(settleTask)
        overlay.detach()
        translator.close()
        logi("service unbound")
        return super.onUnbind(intent)
    }
}
