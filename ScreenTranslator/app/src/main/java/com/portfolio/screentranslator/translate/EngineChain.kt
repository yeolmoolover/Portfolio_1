package com.portfolio.screentranslator.translate

import android.util.Log
import com.portfolio.screentranslator.Prefs
import java.security.MessageDigest

/**
 * Picks the engine order from settings and falls through on quota / failure:
 *   primary (Gemini or Claude) -> Gemma (free, huge quota) -> ML Kit (offline).
 * Also caches results by the OCR text so re-visiting a screen costs nothing.
 */
class EngineChain(private val prefs: Prefs) {

    val gemini = GeminiEngine("gemini", { prefs.geminiKey }, { prefs.geminiModel })
    val gemma = GeminiEngine("gemma", { prefs.geminiKey }, { prefs.gemmaModel }, isGemma = true)
    val claude = ClaudeEngine({ prefs.claudeKey }, { prefs.claudeModel })
    val mlkit = MlKitEngine()

    private val cache = object : LinkedHashMap<String, TranslationResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResult>?) = size > 200
    }

    private val history = ArrayDeque<Pair<String, String>>()

    var lastError: String? = null
        private set

    fun engineByName(n: String): TranslationEngine = when (n) {
        "claude" -> claude
        "gemma" -> gemma
        "mlkit" -> mlkit
        else -> gemini
    }

    private fun order(): List<TranslationEngine> {
        val list = ArrayList<TranslationEngine>()
        when (prefs.primaryEngine) {
            "claude" -> { list += claude; if (prefs.geminiKey.isNotBlank()) { list += gemini; list += gemma } }
            "mlkit" -> {}
            else -> { list += gemini; list += gemma; if (prefs.claudeKey.isNotBlank()) list += claude }
        }
        list += mlkit
        return list
    }

    fun cacheKey(req: TranslationRequest, comic: Boolean): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(if (comic) 1 else 0)
        for (b in req.blocks) md.update(b.text.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun cached(key: String): TranslationResult? = synchronized(cache) { cache[key] }

    fun historySnapshot(): List<Pair<String, String>> = synchronized(history) { history.toList() }

    /** Runs the chain. Throws only when every engine failed. */
    suspend fun translate(req: TranslationRequest, cacheKey: String): TranslationResult {
        cached(cacheKey)?.let { return it }
        val errors = ArrayList<String>()
        for (engine in order()) {
            if (engine === gemini && prefs.geminiExhaustedToday) { errors += "gemini: 오늘 한도 소진"; continue }
            if (engine === gemma && prefs.geminiKey.isBlank()) continue
            if (engine === claude && prefs.claudeKey.isBlank()) continue
            val r = if (engine.supportsImage) req else req.copy(imageJpeg = null)
            try {
                val result = engine.translate(r)
                prefs.bumpUsage(engine.name)
                remember(req, result)
                synchronized(cache) { cache[cacheKey] = result }
                lastError = if (errors.isEmpty()) null else errors.joinToString(" / ")
                return result
            } catch (e: QuotaExceededException) {
                if (engine === gemini) prefs.markGeminiExhausted()
                errors += "${engine.name}: ${e.message}"
            } catch (e: EngineUnavailableException) {
                errors += "${engine.name}: ${e.message}"
            } catch (e: Exception) {
                Log.w("EngineChain", "${engine.name} failed", e)
                errors += "${engine.name}: ${e.javaClass.simpleName} ${e.message?.take(80)}"
            }
        }
        lastError = errors.joinToString(" / ")
        throw EngineUnavailableException(lastError ?: "모든 엔진 실패")
    }

    /** Runs one specific engine, bypassing cache and fallback (used by the compare view). */
    suspend fun translateWith(engine: TranslationEngine, req: TranslationRequest): TranslationResult {
        val r = if (engine.supportsImage) req else req.copy(imageJpeg = null)
        val result = engine.translate(r)
        prefs.bumpUsage(engine.name)
        return result
    }

    private fun remember(req: TranslationRequest, result: TranslationResult) {
        if (req.blocks.isEmpty()) return
        val src = req.blocks.joinToString(" | ") { it.text }
        val ko = result.items.filter { !it.isSfx }.joinToString(" | ") { it.text }
        synchronized(history) {
            history.addLast(src to ko)
            while (history.size > 3) history.removeFirst()
        }
    }
}
