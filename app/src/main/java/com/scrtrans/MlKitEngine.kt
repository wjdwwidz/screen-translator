package com.scrtrans

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/** On-device ML Kit, Japanese to Korean. */
class MlKitEngine : TranslationEngine {

    private var translator: Translator? = null

    @Volatile
    private var ready = false

    /**
     * Requests that arrived before the models did. Held rather than dropped, so the
     * first screen fills in as soon as the download finishes instead of staying
     * Japanese until something happens to trigger another lookup.
     */
    private val pending = ArrayList<Pair<String, (String) -> Unit>>()

    override fun warmUp(onReady: (Boolean) -> Unit) {
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
        translator = t
        // First run downloads both language models; needs network exactly once.
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                logi("ML Kit models ready")
                ready = true
                val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (queued.isNotEmpty()) logi("flushing ${queued.size} queued translations")
                queued.forEach { (text, cb) -> run(text, cb) }
                onReady(true)
            }
            .addOnFailureListener { e ->
                loge("ML Kit model download failed (offline?)", e)
                onReady(false)
            }
    }

    override fun translate(text: String, onResult: (String) -> Unit) {
        if (!ready) {
            synchronized(pending) {
                if (pending.size < 500) pending.add(text to onResult)
            }
            return
        }
        run(text, onResult)
    }

    private fun run(text: String, onResult: (String) -> Unit) {
        val t = translator ?: return
        t.translate(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { e -> logw("translate failed: $text", e) }
    }

    override fun close() {
        synchronized(pending) { pending.clear() }
        ready = false
        translator?.close()
        translator = null
    }
}
