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

        // An offer about one app must not end up sitting over the next one.
        DetectionSheet.dismissIfNotFor(pkg)

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
        lastItems = carryColours(TextCollector.collect(root, screenW, screenH, probe))
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val located = lastItems.count { it.hasInk }
        logi("collected ${lastItems.size} items, $located located, probe=$probe (${"%.0f".format(ms)}ms)")
        render()

        if (probe && ColorSampler.ENABLED && located > 0) sampleColors()

        // Anything still unlocated is either waiting on the settle pass or is text the
        // API will not describe. Either way, one more attempt once the screen is quiet.
        if (!probe && located < lastItems.size) {
            handler.removeCallbacks(settleTask)
            handler.postDelayed(settleTask, SETTLE_MS)
        }
    }

    /**
     * Carries the last pass's colours onto the items just collected.
     *
     * A fresh walk of the tree knows no colours, so without this every content change
     * repaints in the default dark-on-white and only goes back to the source's colours
     * once the settle pass has taken its screenshot — a colour flash on every scroll
     * stop, on top of the blank one the screenshot already costs.
     *
     * Keyed by text and box size, the same key the ink cache uses, so a scrolled node
     * keeps its colours. A node that changed colour without changing either — a tab
     * going active — keeps the stale pair until the settle pass overwrites it, which is
     * a far smaller artefact than the flash.
     */
    private fun carryColours(fresh: List<TextItem>): List<TextItem> {
        if (lastItems.isEmpty()) return fresh
        // Both halves of what the screenshot found, so a scroll does not put a covered
        // search icon back for the 450ms until the next settle pass.
        val known = HashMap<String, TextItem>(lastItems.size)
        for (item in lastItems) if (item.colors != null) known[colourKey(item)] = item
        if (known.isEmpty()) return fresh
        return fresh.map {
            val seen = known[colourKey(it)] ?: return@map it
            it.copy(colors = seen.colors, inkLeft = seen.inkLeft, inkRight = seen.inkRight)
        }
    }

    private fun colourKey(item: TextItem) =
        "${item.text}|${item.bounds.width()}x${item.bounds.height()}"

    /**
     * Reads the source's colours off the screen and redraws in them.
     *
     * The screenshot composites our own overlay, so the overlay comes down for the
     * frame and the translations visibly blink. Nothing else can see past ourselves,
     * and it only happens once a screen has settled.
     */
    private fun sampleColors() {
        val snapshot = lastItems
        overlay.clear()
        handler.postDelayed({
            ColorSampler.sample(this, snapshot) { coloured ->
                handler.post {
                    // A new screen may have arrived while the shot was in flight; its
                    // items win, and rendering either way puts the overlay back up.
                    if (lastItems === snapshot) lastItems = coloured
                    render()
                }
            }
        }, ColorSampler.CLEAR_MS)
    }

    /** Re-maps the current items through the translator. Cheap: it is a cache lookup. */
    private fun render() {
        if (!lastWasTarget) return
        overlay.show(
            lastItems.map { item ->
                val ko = translator.translateOrNull(item.text)
                RenderItem(
                    ko ?: item.text, item.bounds, ko != null,
                    item.inkLines, item.sourceLineHeight, item.sourceEmSize,
                    item.container, item.colors, item.inkLeft, item.inkRight,
                )
            }
        )
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        handler.removeCallbacks(collectTask)
        handler.removeCallbacks(settleTask)
        DetectionSheet.dismiss()
        overlay.detach()
        translator.close()
        logi("service unbound")
        return super.onUnbind(intent)
    }
}
