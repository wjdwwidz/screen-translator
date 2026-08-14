package com.scrtrans

import android.content.Context

/**
 * The one switch [LlmEngine] answers to.
 *
 * Off by default, and deliberately so. The weights are a 2.6GB file that has to arrive
 * from somewhere, and running them costs about a gigabyte of memory inside a background
 * service — neither is something to start doing on a user's behalf. ML Kit alone is the
 * product; this is the upgrade.
 *
 * Read on the translate path, so the value is cached rather than fetched from
 * SharedPreferences per string. The activity and the service share a process, so a write
 * from the UI is visible to the service immediately.
 */
object LlmSettings {

    private const val PREFS = "llm"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var cached: Boolean? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean =
        cached ?: prefs(context).getBoolean(KEY_ENABLED, false).also { cached = it }

    fun setEnabled(context: Context, on: Boolean) {
        cached = on
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        logi("LLM ${if (on) "enabled" else "disabled"}")
    }
}
