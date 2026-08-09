package com.scrtrans

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache, in-flight de-duplication and the synchronous lookup window, in one place.
 *
 * None of this depends on which engine is underneath, so it lives here and only here.
 * [onTranslated] fires when a new result lands so the overlay can redraw.
 */
class CachingTranslator(
    private val engine: TranslationEngine,
    private val onTranslated: () -> Unit,
) : TextTranslator {

    private val cache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override fun warmUp(onReady: (Boolean) -> Unit) = engine.warmUp(onReady)

    override fun translateOrNull(text: String): String? {
        cache[text]?.let { return it }

        // No readiness gate here on purpose: a glossary hit needs no engine, so it must
        // still resolve while the models are missing. Waiting for readiness is the
        // engine's own problem.
        // Same string appears many times per screen and across redraws; one request each.
        if (inFlight.add(text)) {
            engine.translate(text) { result ->
                cache[text] = result
                inFlight.remove(text)
                onTranslated()
            }
        }
        return null
    }

    override fun close() {
        engine.close()
        cache.clear()
        inFlight.clear()
    }
}
