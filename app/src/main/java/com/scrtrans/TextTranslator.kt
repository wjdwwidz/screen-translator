package com.scrtrans

/**
 * What the drawing path talks to.
 *
 * onDraw needs a string *now*, but translation is asynchronous. [translateOrNull]
 * therefore answers immediately: the translation if it is already known, else null,
 * in which case the caller draws the source text and waits for the redraw that
 * follows when the result lands. Keeping this synchronous-with-null rather than
 * `translate(text): String` is what lets a 0ms local engine and a several-hundred-ms
 * network engine (DeepL and friends) swap in without the drawing code changing.
 */
interface TextTranslator {
    fun warmUp(onReady: (Boolean) -> Unit)
    fun translateOrNull(text: String): String?
    fun close()
}

/** The actual engine. Results arrive by callback. */
interface TranslationEngine {
    fun warmUp(onReady: (Boolean) -> Unit)
    fun translate(text: String, onResult: (String) -> Unit)
    fun close()
}
