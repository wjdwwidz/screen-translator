package com.scrtrans

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Appends every source/result pair to translations.jsonl so quality can be judged
 * from the record instead of from memory.
 *
 *   adb pull /sdcard/Android/data/com.scrtrans/files/translations.jsonl
 *
 * `fromGlossary` marks entries the engine never saw; `guessed` narrows that to the
 * glossary entries that were never verified against a real screen — the ones worth
 * reviewing first.
 */
object TranslationLog {

    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private val seen = HashSet<String>()

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        file = File(dir, "translations.jsonl")
        logi("translation log: ${file?.absolutePath}")
    }

    fun record(source: String, result: String, fromGlossary: Boolean, guessed: Boolean) {
        val f = file ?: return
        synchronized(seen) {
            if (!seen.add(source)) return // one line per distinct source string
        }
        io.execute {
            try {
                f.appendText(
                    """{"source":${quote(source)},"result":${quote(result)},""" +
                        """"fromGlossary":$fromGlossary,"guessed":$guessed}""" + "\n"
                )
            } catch (e: Exception) {
                logw("log write failed", e)
            }
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
