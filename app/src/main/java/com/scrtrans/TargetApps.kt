package com.scrtrans

import android.content.Context
import android.content.SharedPreferences

/**
 * Which apps we know about, and which of them translation is switched on for.
 *
 * There is one list in the UI, not two. [BUILT_IN] ships in the code and starts on;
 * anything [JapaneseScout] finds joins the same list and starts off. So declining an
 * app is just leaving its switch alone — the row stays, and turning it on later needs
 * no hunting for the app and no reopening it.
 *
 * [ENABLED] and [DISABLED] are both stored because the default differs by row: a
 * built-in is on unless it was switched off, a detected app is off unless it was
 * switched on. One set could not express both.
 *
 * The service and the activity share a process, so a write here is visible to the
 * service on its next pass without any broadcast.
 */
object TargetApps {

    /** Apps we ship support for, glossary and all. On by default; can still be switched off. */
    val BUILT_IN = setOf(
        "jp.hotpepper.android.beauty.hair",
    )

    private const val PREFS = "targets"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_DETECTED = "detected"

    private lateinit var prefs: SharedPreferences

    @Volatile
    private var cachedTargets: Set<String> = BUILT_IN

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        refresh()
    }

    /**
     * Read on every collect pass, so it is a field rather than a set rebuilt each time.
     * Falls back to the built-ins if the service somehow runs before [init].
     */
    fun targets(): Set<String> = cachedTargets

    /** Every app worth a row in the UI, on or off. */
    fun known(): Set<String> = BUILT_IN + read(KEY_ENABLED) + read(KEY_DETECTED)

    /** Apps the scout found Japanese in, whether or not translation was ever switched on. */
    fun detected(): Set<String> = read(KEY_DETECTED)

    fun isEnabled(pkg: String): Boolean = pkg in cachedTargets

    fun setEnabled(pkg: String, on: Boolean) {
        if (on) {
            write(KEY_ENABLED, read(KEY_ENABLED) + pkg)
            write(KEY_DISABLED, read(KEY_DISABLED) - pkg)
        } else {
            write(KEY_ENABLED, read(KEY_ENABLED) - pkg)
            write(KEY_DISABLED, read(KEY_DISABLED) + pkg)
        }
        refresh()
        logi("target $pkg -> ${if (on) "on" else "off"}")
    }

    /** Records a scout hit. Kept for good, so a row never disappears from the list. */
    fun markDetected(pkg: String) {
        if (pkg in read(KEY_DETECTED)) return
        write(KEY_DETECTED, read(KEY_DETECTED) + pkg)
        logi("japanese detected in: $pkg")
    }

    private fun refresh() {
        cachedTargets = (BUILT_IN + read(KEY_ENABLED)) - read(KEY_DISABLED)
    }

    // getStringSet hands back the live instance and forbids mutating it, so both sides
    // of a read/modify/write copy.
    private fun read(key: String): Set<String> =
        if (!::prefs.isInitialized) emptySet()
        else prefs.getStringSet(key, null)?.toSet() ?: emptySet()

    private fun write(key: String, value: Set<String>) {
        if (!::prefs.isInitialized) return
        prefs.edit().putStringSet(key, HashSet(value)).apply()
    }
}
