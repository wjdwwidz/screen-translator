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

    fun record(
        source: String,
        result: String,
        fromGlossary: Boolean,
        guessed: Boolean,
        engine: String = "mlkit",
    ) {
        val f = file ?: return
        synchronized(seen) {
            // One line per distinct source string, per engine: the LLM pass answers the
            // same source a second time, and comparing the two is the point of the file.
            if (!seen.add("$engine $source")) return
        }
        // Only the engine's output is suspect; glossary entries were written by hand.
        val lostNegation = !fromGlossary && NegationCheck.lostNegation(source, result)
        if (lostNegation) logw("negation lost: \"$source\" -> \"$result\"")
        io.execute {
            try {
                f.appendText(
                    """{"source":${quote(source)},"result":${quote(result)},""" +
                        """"engine":${quote(engine)},""" +
                        """"fromGlossary":$fromGlossary,"guessed":$guessed,""" +
                        """"lostNegation":$lostNegation}""" + "\n"
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
