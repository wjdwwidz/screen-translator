package com.scrtrans

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches apps we do not translate and works out which of them are actually Japanese.
 *
 * The point is to be quiet. A Korean app with one Japanese song title in it, or a store
 * listing quoting a Japanese review, must never turn into "translate this app?" — an
 * offer that fires on noise is worse than no offer, because it trains the user to
 * dismiss the one that matters. So a package has to clear three separate bars:
 *
 *  1. **Enough Japanese on one screen** — [MIN_JA_NODES] strings, and they must be at
 *     least [MIN_JA_RATIO] of the strings on that screen. A stray line in a Korean UI
 *     fails the ratio; a mostly-empty screen with two labels fails the count.
 *  2. **Kana, not just kanji** — kanji are shared with Chinese, so a screen that is all
 *     kanji says "CJK" and nothing more. See [containsKana].
 *  3. **More than one screen** — [QUALIFYING_SCREENS] distinct screens have to clear
 *     bars 1 and 2. One Japanese page inside an otherwise Korean app is not an app
 *     worth translating.
 *
 * Cost matters here because this runs for every app the user opens, not just ours.
 * A scan is capped at [MAX_NODES] and throttled to one per package per
 * [SCAN_INTERVAL_MS]; a package that qualifies is never scanned again.
 */
object JapaneseScout {

    private const val MIN_JA_NODES = 6
    private const val MIN_JA_RATIO = 0.4
    private const val QUALIFYING_SCREENS = 3

    /**
     * A ceiling on the cost, not a target. Real screens come in far under it — the
     * hotpepper list screen is 216 nodes — but the walk is depth-first, so a deep tree
     * could otherwise spend the whole budget in one branch and judge the app on it.
     * Still under a third of what TextCollector walks, and only once per app per
     * [SCAN_INTERVAL_MS].
     */
    private const val MAX_NODES = 1200
    private const val SCAN_INTERVAL_MS = 3_000L

    /** Distinct qualifying screens seen per package, by screen signature. */
    private val qualifying = HashMap<String, MutableSet<Int>>()
    private val lastScan = HashMap<String, Long>()

    /** Packages ruled out for good: no launcher entry (system UI, keyboards), or ourselves. */
    private val ineligible = HashSet<String>()

    /**
     * Called on every pass over an app we are not translating. Returns quickly for the
     * overwhelming majority of those passes without touching the tree.
     */
    fun observe(context: Context, pkg: String, root: AccessibilityNodeInfo) {
        if (pkg in ineligible) return

        // Throttle first: the checks below read prefs, and this runs on every pass over
        // every app on the device, most of which are the same app milliseconds apart.
        val now = System.currentTimeMillis()
        val last = lastScan[pkg] ?: 0L
        if (now - last < SCAN_INTERVAL_MS) return
        lastScan[pkg] = now

        if (pkg in TargetApps.detected()) return // already offered; nothing left to learn

        if (!eligible(context, pkg)) {
            ineligible.add(pkg)
            return
        }

        val screen = scan(root) ?: return

        val seen = qualifying.getOrPut(pkg) { HashSet() }
        if (!seen.add(screen)) return // same screen again, not new evidence
        logi("scout: $pkg qualifying screen ${seen.size}/$QUALIFYING_SCREENS")
        if (seen.size >= QUALIFYING_SCREENS) {
            TargetApps.markDetected(pkg)
            qualifying.remove(pkg)
        }
    }

    /**
     * Walks the visible text of one screen and returns a signature for it if it clears
     * bars 1 and 2, or null if it does not.
     */
    private fun scan(root: AccessibilityNodeInfo): Int? {
        var textNodes = 0
        var jaNodes = 0
        var kana = false
        var visited = 0
        // Folded over every Japanese string on the screen, not the first few. A signature
        // built from the first strings the walk happens to reach is a signature of the
        // header and the tab bar, which are exactly the parts that do not change when
        // the user moves around the app — so every screen would look like the same one.
        var signature = 17

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                textNodes++
                if (containsJapanese(text)) {
                    jaNodes++
                    if (!kana && containsKana(text)) kana = true
                    signature = signature * 31 + text.hashCode()
                }
            }

            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }

        val ratio = if (textNodes == 0) 0.0 else jaNodes.toDouble() / textNodes
        val passed = kana && jaNodes >= MIN_JA_NODES && ratio >= MIN_JA_RATIO
        // The thresholds are the whole design here, so leave a trail to tune them from.
        logd("scout scan: ja=$jaNodes/$textNodes ratio=${"%.2f".format(ratio)} kana=$kana nodes=$visited -> $passed")
        if (!passed) return null
        return signature
    }

    /**
     * Only apps the user can launch from the home screen. That is what rules out the
     * system UI, the keyboard, and the permission dialogs — all of which are on screen
     * constantly and none of which anyone wants offered as a translation target.
     */
    private fun eligible(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return false
        return try {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (e: Exception) {
            false
        }
    }
}
