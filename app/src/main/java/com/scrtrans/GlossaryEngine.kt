package com.scrtrans

/** Answers from the glossary when it can, otherwise hands off to the real engine. */
class GlossaryEngine(private val delegate: TranslationEngine) : TranslationEngine {

    override fun warmUp(onReady: (Boolean) -> Unit) = delegate.warmUp(onReady)

    override fun translate(text: String, onResult: (String) -> Unit) {
        val hit = Glossary.lookup(text)
        if (hit != null) {
            TranslationLog.record(text, hit.result, fromGlossary = true, guessed = hit.guessed)
            onResult(hit.result)
            return
        }
        delegate.translate(text) { result ->
            TranslationLog.record(text, result, fromGlossary = false, guessed = false)
            onResult(result)
        }
    }

    override fun close() = delegate.close()
}
