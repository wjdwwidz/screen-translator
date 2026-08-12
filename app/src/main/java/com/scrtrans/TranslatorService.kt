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
         * runs once events stop, fills in what the cache could not answer and reads the
         * source's colours.
         *
         * It was 450ms, chosen when this pass only had to finish before the user noticed
         * an unlocated box. It also gates the colours, and there the wait was most of the
         * delay: measured 996ms from the first render to `coloured` on a 24-item list, of
         * which 450ms was this timer idling. 200ms is still longer than the gap between
         * the events a single scroll emits — every one of them reschedules this — so it
         * does not fire mid-scroll, and it takes the same screen to 748ms.
         */
        private const val SETTLE_MS = 200L

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
        eventSeq++
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

        if (probe && ColorSampler.ENABLED && located > 0 && screenChangedSinceShot()) sampleColors()

        // Scheduled whatever the walk found, where this used to ask for an unlocated box
        // first. Probing is no longer the only thing the pass is for: it is also the only
        // thing that reads colours, so gating it on the probe's own work left a screen the
        // ink cache could answer in full with no screenshot at all, sitting in the default
        // black-on-white for as long as it was up. Measured: re-entering a list already
        // walked once collected 24 items, 24 located, and no `coloured` line ever followed.
        //
        // The pass this adds is the cheap one, since by definition nothing needs probing:
        // measured 9-37ms, against 93-176ms when it has strings to look up. It reaches the
        // screenshot only when [screenChangedSinceShot] says so, so a screen sitting still
        // costs the walk and nothing else however many times this fires on it.
        if (!probe) {
            handler.removeCallbacks(settleTask)
            handler.postDelayed(settleTask, SETTLE_MS)
        }
    }

    /**
     * Whether a screenshot would tell us anything we do not already know: one screen, one
     * reading, and no second look until the screen is a different one.
     *
     * The shot costs a blank frame — the overlay has to come down for it, measured 370-386ms
     * from the probe pass to `coloured` — so it has to be earned, and a screen that has
     * already been photographed as it is now cannot earn another. That covers the idle
     * repaint and the once-a-minute clock tick, which is where the waste was.
     *
     * It deliberately does *not* ask whether a colour is missing, which is what it asked
     * first and got wrong. A reading can be wrong as easily as absent — a shot that lands
     * while the screen is still settling reads the pixels of a layout that has moved on,
     * and [colourCache] would then hold that wrong colour for the session with nothing
     * ever scheduled to correct it. Measured: the section headers 「主なキーワードで探す」
     * and 「ヘアスタイル動画で見る」 came back with a white surface against the grey band
     * they actually sit on, `coloured 24/24` with nothing missing, and stayed white
     * through every later pass. Asking "is this the screen I last photographed" instead
     * re-reads on arrival, so a bad reading survives exactly one screen.
     *
     * The same question also retires the "shoot forever" case: a screen holding an item
     * whose colour is genuinely unreadable — text over a photo — would otherwise fail the
     * missing-colour test on every settle and blink every time for a colour that is never
     * coming.
     */
    private fun screenChangedSinceShot() =
        lastItems.mapTo(HashSet(lastItems.size)) { colourKey(it) } != shotKeys

    /** The keys the last filed screenshot covered. See [screenChangedSinceShot]. */
    private var shotKeys: Set<String> = emptySet()

    /**
     * Bumped by every accessibility event, so a screenshot can tell whether the screen
     * held still for it. See [sampleColors].
     */
    private var eventSeq = 0

    /** What one screenshot learned about one item. See [colourCache] for the offsets. */
    private class Sample(val colors: SourceColors, val spanLeft: Int, val spanRight: Int)

    /**
     * Every colour read this session, keyed and capped exactly like [TextCollector]'s ink
     * cache — the screenshot is the expensive half of the same question that cache answers
     * cheaply, so it has no business having a shorter memory.
     *
     * This used to be the previous pass's items and nothing more, which meant it only
     * ever survived a scroll. Leaving a screen and coming back dropped every colour, and
     * on a screen the ink cache could answer in full no shot followed to put them back.
     *
     * The span is held as an offset from the box's left edge rather than the screen
     * coordinate [ColorSampler] returns. Same reasoning as the ink cache: the key pins the
     * box's size, so across two sightings the box can only have been translated, and an
     * offset survives that where an absolute x would be carried onto a node that has moved.
     */
    private val colourCache = object : LinkedHashMap<String, Sample>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sample>) =
            size > 400
    }

    /**
     * Puts known colours onto the items just collected.
     *
     * A fresh walk of the tree knows no colours, so without this every content change
     * repaints in the default dark-on-white and only goes back to the source's colours
     * once the settle pass has taken its screenshot — a colour flash on every scroll
     * stop, on top of the blank one the screenshot already costs.
     */
    private fun carryColours(fresh: List<TextItem>): List<TextItem> {
        if (colourCache.isEmpty()) return fresh
        return fresh.map { item ->
            val seen = colourCache[colourKey(item)] ?: return@map item
            val hasSpan = seen.spanRight > seen.spanLeft
            item.copy(
                colors = seen.colors,
                inkLeft = if (hasSpan) item.bounds.left + seen.spanLeft else 0,
                inkRight = if (hasSpan) item.bounds.left + seen.spanRight else 0,
            )
        }
    }

    /** Files what the screenshot just read, so the next sighting needs no screenshot. */
    private fun rememberColours(items: List<TextItem>) {
        for (item in items) {
            val colors = item.colors ?: continue
            // Only the no-ink path fills a span in; for the rest the two are left at 0.
            val hasSpan = item.inkLeft > 0 && item.inkRight > item.inkLeft
            colourCache[colourKey(item)] = Sample(
                colors,
                if (hasSpan) item.inkLeft - item.bounds.left else 0,
                if (hasSpan) item.inkRight - item.bounds.left else 0,
            )
        }
    }

    private fun colourKey(item: TextItem) =
        "${item.text}|${item.bounds.width()}x${item.bounds.height()}"

    /**
     * Reads the source's colours off the screen and redraws in them.
     *
     * The screenshot composites our own overlay, so the overlay comes down for the
     * frame and the translations visibly blink. Nothing else can see past ourselves,
     * and it only happens once a screen has settled and [screenChangedSinceShot] says
     * this is not the screen we last photographed.
     */
    private fun sampleColors() {
        val snapshot = lastItems
        val root = rootInActiveWindow ?: return
        val windowId = root.windowId
        val windowBounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }

        // Taking the overlay down for the shot is what showed the Japanese underneath for
        // a frame. A window-only shot cannot see our overlay, so there is nothing to hide
        // and nothing to wait for; only the older path still pays that price.
        val needsClearing = !ColorSampler.canShootWindow(windowId)
        if (needsClearing) overlay.clear()
        handler.postDelayed({
            // Everything below is about one question: did the screen hold still from here
            // until the pixels came back? A reading taken across a change pairs one
            // layout's boxes with another's pixels, and this cache is read for the rest
            // of the session, where the carry it replaces was thrown away next pass.
            val shutter = eventSeq
            ColorSampler.sample(this, snapshot, windowId, windowBounds) { coloured ->
                handler.post {
                    // Identity alone is not enough. An event arriving mid-shot only
                    // reaches [lastItems] a DEBOUNCE_MS later, which is longer than the
                    // shot, so the list still looks untouched at exactly the moment it is
                    // known to be stale. The counter sees what the list cannot yet.
                    val held = lastItems === snapshot && eventSeq == shutter
                    if (held) {
                        lastItems = coloured
                        rememberColours(coloured)
                        shotKeys = coloured.mapTo(HashSet(coloured.size)) { colourKey(it) }
                    }
                    render()
                }
            }
        }, if (needsClearing) ColorSampler.CLEAR_MS else 0L)
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
