package com.scrtrans

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A second pass over long body text, on top of whatever [delegate] already answered.
 *
 * ML Kit has no ja->ko model and routes ja->en->ko, and the detour is what the glossary
 * and [NegationCheck] exist to patch. An LLM translating directly does not take the
 * detour — measured on real screens, it wins clearly on sentences. It also has no floor:
 * it drifts into Chinese, rewrites currency, leaves Japanese standing. So it is added
 * rather than swapped in, and only where it was shown to win.
 *
 * The shape follows from that. Every string still goes to [delegate] first and is drawn
 * the moment it lands, so nothing here can delay or blank the overlay. Long strings are
 * then queued, and a better answer replaces the drawn one seconds later — CachingTranslator
 * overwrites its cache entry and calls back for a redraw, which is the same path a slow
 * network engine would use. If the model is missing, disabled or broken, the overlay is
 * exactly what it was before.
 *
 * Short labels are left alone on purpose: that is where the LLM regressed against both
 * the glossary and ML Kit, and where the user is least willing to wait.
 *
 * Memory is why the engine is not just held open. The weights cost about a gigabyte of
 * dirty memory, and this runs inside an accessibility service — a background process,
 * which is what Android reclaims first, with the app being translated in front of it.
 * So the engine is loaded on the first string that needs it and closed again after
 * [IDLE_MS] of quiet, leaving the service at its normal size while the user reads.
 */
class LlmEngine(
    private val delegate: TranslationEngine,
    private val modelPath: String,
    private val cacheDir: String,
    private val enabled: () -> Boolean,
) : TranslationEngine {

    companion object {
        /** Close the model after this long with nothing to translate. */
        private const val IDLE_MS = 30_000L

        /**
         * Below this, the string is a label rather than body text. Counted in Japanese
         * characters, which carry far more than their number suggests — six of them is
         * already a sentence.
         *
         * Six because that is where the 〜たい menu lines start, and they are the worst of
         * the ja->en->ko detour: 体の歪みをとりたい came back as "나는 시체 왜곡을하고 싶다",
         * 眼精疲労を解消したい as "나는 눈 증거 피로를 제거하고 싶다". Twelve, chosen earlier
         * off a single example, missed the whole category. Against that, it sends 81% of
         * what reaches the engine to the LLM rather than 54%.
         */
        private const val MIN_CHARS = 6

        /** Enough to hold the longest review line and its answer. */
        private const val MAX_TOKENS = 1024

        /**
         * A UI string's translation is short. Capping the decode keeps a model that has
         * started rambling from holding the queue for a minute.
         */
        private const val MAX_OUTPUT_TOKENS = 256

        /** Kept short: every token here is prefill, paid once per conversation. */
        private const val INSTRUCTION =
            "Translate the Japanese text the user sends into natural Korean. " +
                "It is text from a beauty salon booking app. " +
                "Reply with the Korean translation only, no explanation."

        /** A backlog this deep already means the screen changed; newer work matters more. */
        private const val MAX_QUEUE = 32

        private const val MODEL_NAME = "model.litertlm"

        /**
         * Where the weights are. Too large for the APK at 2.6GB, so they arrive out of
         * band — the app's own external files directory first, which needs no root:
         *
         *     adb push gemma-4-E2B-it.litertlm \
         *         /sdcard/Android/data/com.scrtrans/files/model.litertlm
         *
         * Falling back to the shell's tmp directory, which is where a copy pulled from
         * another app on the device tends to land.
         */
        fun findModel(context: Context): String? {
            val candidates = listOfNotNull(
                context.getExternalFilesDir(null)?.let { File(it, MODEL_NAME) },
                File("/data/local/tmp/llm/$MODEL_NAME"),
            )
            val found = candidates.firstOrNull { it.canRead() && it.length() > 0 }
            if (found == null) {
                logi("LLM no model — looked in ${candidates.joinToString { it.absolutePath }}")
                return null
            }
            logi("LLM model ${found.length() / 1024 / 1024}MB at ${found.absolutePath}")
            return found.absolutePath
        }

        /**
         * Body text, not a label. Length alone gets most of it; a sentence terminator
         * catches the short ones that are still sentences ("空席あり。").
         *
         * Katakana-only strings are held back however long they run. They are loanwords —
         * style names, mostly — and they are the LLM's worst case, the one place the
         * glossary beat it outright (まつげ, リラク, 口コミ). A long one is a catalogue of
         * style names rather than a sentence, so length says nothing useful about it.
         */
        fun isBodyText(text: String): Boolean {
            if (isKatakanaOnly(text)) return false
            return text.length >= MIN_CHARS || text.any { it == '。' || it == '！' || it == '？' }
        }

        /**
         * Whether to believe the model. The failures seen on real screens were not subtle
         * — whole lines came back in Chinese, or the Japanese was passed through
         * untouched. Both leave the result without Hangul or still carrying kana and han,
         * so the check is cheap and the fallback is ML Kit's answer, already on screen.
         */
        fun looksKorean(result: String): Boolean {
            if (result.isBlank()) return false
            val hangul = result.any { it.code in 0xAC00..0xD7A3 }
            return hangul && !containsJapanese(result)
        }
    }

    private class Job(val text: String, val onResult: (String) -> Unit)

    private val queue = LinkedBlockingQueue<Job>(MAX_QUEUE)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var closed = false

    override fun warmUp(onReady: (Boolean) -> Unit) = delegate.warmUp(onReady)

    override fun translate(text: String, onResult: (String) -> Unit) {
        // Always first, always drawn: the LLM is an improvement on this, never a
        // precondition for it.
        delegate.translate(text, onResult)

        if (closed || !enabled() || !isBodyText(text)) return
        if (!queue.offer(Job(text, onResult))) {
            logd("LLM queue full, skipping \"${text.take(20)}\"")
            return
        }
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (closed || worker != null) return
        worker = thread(name = "llm-engine") { workLoop() }
    }

    /**
     * Owns the engine for as long as there is work. Loading is the expensive part, so it
     * is paid once for a burst of strings and given back when the burst ends.
     */
    private fun workLoop() {
        var engine: Engine? = null
        try {
            while (!closed) {
                val job = queue.poll(IDLE_MS, TimeUnit.MILLISECONDS)
                if (job == null) break // idle: fall through and give the memory back

                val e = engine ?: load() ?: break
                engine = e

                val translated = translate(e, job.text) ?: continue
                job.onResult(translated)
            }
        } finally {
            engine?.let {
                try {
                    it.close()
                } catch (t: Throwable) {
                    logw("LLM close failed", t)
                }
                logi("LLM engine unloaded")
            }
            synchronized(this) { worker = null }
            // A string may have arrived while the engine was being closed.
            if (!closed && queue.isNotEmpty()) startWorker()
        }
    }

    private fun load(): Engine? {
        val t0 = System.nanoTime()
        return try {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    // The overlay's backend. CPU is the fallback the runtime picks itself
                    // when a device has no usable GPU path.
                    backend = Backend.GPU(),
                    // Holds the compiled program so a later load skips the compile.
                    cacheDir = cacheDir,
                    maxNumTokens = MAX_TOKENS,
                ),
            ).also {
                it.initialize()
                logi("LLM engine loaded in ${(System.nanoTime() - t0) / 1_000_000}ms")
            }
        } catch (t: Throwable) {
            // Includes the case where the file is not a model at all. One complaint, then
            // the overlay carries on with ML Kit.
            loge("LLM load failed — staying on ML Kit", t)
            null
        }
    }

    private fun translate(engine: Engine, text: String): String? {
        val t0 = System.nanoTime()
        val raw = try {
            // A conversation rather than a bare session: the instruction-tuned weights
            // expect their chat template around the turn, and Conversation is what applies
            // it. A session fed the raw prompt answers with an empty string.
            //
            // One per string, so these unrelated labels cannot see each other's answers.
            engine.createConversation(
                ConversationConfig(
                    // Carried by the conversation instead of prepended to every message,
                    // so the wording is not re-tokenised per string.
                    systemInstruction = Contents.of(INSTRUCTION),
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                ),
            ).use { conversation ->
                conversation.sendMessage(text).text()
            }
        } catch (t: Throwable) {
            logw("LLM generate failed for \"${text.take(20)}\"", t)
            return null
        }
        val ms = (System.nanoTime() - t0) / 1_000_000

        val cleaned = clean(raw)
        if (!looksKorean(cleaned)) {
            logw("LLM rejected after ${ms}ms: \"${text.take(20)}\" -> \"${cleaned.take(40)}\"")
            return null
        }
        logi("LLM ${ms}ms \"${text.take(20)}\" -> \"${cleaned.take(40)}\"")
        TranslationLog.record(text, cleaned, fromGlossary = false, guessed = false, engine = "llm")
        return cleaned
    }

    /** The reply is a Message; its text is whatever text parts it carries. */
    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     * Instruction-tuned models like to introduce themselves. Anything past a blank line
     * is commentary, and wrapping quotes are the model's, not the source's.
     */
    private fun clean(raw: String): String =
        raw.trim()
            .substringBefore("\n\n")
            .trim()
            .removeSurrounding("\"")
            .trim()

    override fun close() {
        closed = true
        queue.clear()
        worker?.interrupt()
        delegate.close()
    }
}
