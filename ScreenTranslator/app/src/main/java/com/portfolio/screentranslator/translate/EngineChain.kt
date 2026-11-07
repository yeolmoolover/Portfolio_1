package com.portfolio.screentranslator.translate

import android.content.Context
import android.util.Log
import com.portfolio.screentranslator.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Picks the engine order from settings and falls through on quota / failure:
 *   primary (Gemini or Claude) -> Gemma (free, huge quota) -> the other LLM if it has a key.
 * Also caches results by the OCR text so re-visiting a screen costs nothing.
 */
class EngineChain(private val prefs: Prefs, context: Context? = null) {

    /**
     * Block-level translation memory: normalised source text -> Korean. Persisted to disk, so a
     * bubble seen before (same page revisited, or a repeated line) is filled instantly and only
     * new bubbles are sent to the model.
     */
    private val cacheFile: File? = context?.let { File(it.filesDir, "block_cache.json") }
    private val blockCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 20000
    }
    @Volatile private var dirty = false

    init { loadCache() }

    private fun loadCache() {
        val f = cacheFile ?: return
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            synchronized(blockCache) { for (k in o.keys()) blockCache[k] = o.getString(k) }
        }.onFailure { Log.w("EngineChain", "cache load failed", it) }
    }

    fun saveCache() {
        val f = cacheFile ?: return
        if (!dirty) return
        val o = JSONObject()
        synchronized(blockCache) { for ((k, v) in blockCache) o.put(k, v) }
        runCatching { File(f.path + ".tmp").apply { writeText(o.toString()) }.renameTo(f) }
            .onFailure { Log.w("EngineChain", "cache save failed", it) }
        dirty = false
    }

    fun cacheSize(): Int = synchronized(blockCache) { blockCache.size }

    /** Normalise OCR text so small recognition differences still hit the same entry. */
    private fun norm(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    private fun lookup(text: String): String? = synchronized(blockCache) { blockCache[norm(text)] }

    val gemini = GeminiEngine("gemini", { prefs.geminiKey }, { prefs.geminiModel })
    val gemma = GeminiEngine("gemma", { prefs.geminiKey }, { prefs.gemmaModel }, isGemma = true)
    val claude = ClaudeEngine({ prefs.claudeKey }, { prefs.claudeModel })


    private val history = ArrayDeque<Pair<String, String>>()

    var lastError: String? = null
        private set

    fun engineByName(n: String): TranslationEngine = when (n) {
        "claude" -> claude
        "gemma" -> gemma
        else -> gemini
    }

    private fun order(): List<TranslationEngine> {
        val list = ArrayList<TranslationEngine>()
        when (prefs.primaryEngine) {
            "claude" -> { list += claude; if (prefs.geminiKey.isNotBlank()) { list += gemini; list += gemma } }
            else -> { list += gemini; list += gemma; if (prefs.claudeKey.isNotBlank()) list += claude }
        }
        return list
    }


    fun historySnapshot(): List<Pair<String, String>> = synchronized(history) { history.toList() }

    /** Outcome of the cache split for the status line. */
    class Outcome(val result: TranslationResult, val fromCache: Int, val requested: Int)

    /**
     * Fills blocks that are already in the translation memory (calling [onPartial] for them at
     * once), sends only the rest through the engine chain, and merges. Throws only when a
     * request was needed and every engine failed.
     */
    suspend fun translate(req: TranslationRequest, onPartial: ((TranslatedText) -> Unit)? = null): Outcome {
        val cachedItems = ArrayList<TranslatedText>()
        val todo = ArrayList<SourceBlock>()
        for (b in req.blocks) {
            val ko = lookup(b.text)
            if (ko != null) cachedItems += TranslatedText(b.id, ko, null, isSfx = false) else todo += b
        }
        for (t in cachedItems) onPartial?.invoke(t)
        if (todo.isEmpty()) {
            lastError = null
            return Outcome(TranslationResult(cachedItems, "cache"), cachedItems.size, 0)
        }
        val sub = req.copy(blocks = todo)
        val result = runChain(sub, onPartial)
        // remember new translations (empty = model says SFX / not text; remember that too)
        val byId = todo.associateBy { it.id }
        synchronized(blockCache) {
            for (t in result.items) if (!t.isSfx) byId[t.sourceId]?.let { blockCache[norm(it.text)] = t.text }
        }
        dirty = true
        saveCache()
        return Outcome(TranslationResult(cachedItems + result.items, result.engineUsed), cachedItems.size, todo.size)
    }

    private suspend fun runChain(req: TranslationRequest, onPartial: ((TranslatedText) -> Unit)?): TranslationResult {
        val errors = ArrayList<String>()
        for (engine in order()) {
            if (engine === gemini && prefs.geminiExhaustedToday) { errors += "gemini: 오늘 한도 소진"; continue }
            if (engine === gemma && prefs.geminiKey.isBlank()) continue
            if (engine === claude && prefs.claudeKey.isBlank()) continue
            val r = if (engine.supportsImage) req else req.copy(imageJpeg = null)
            try {
                val result = engine.translate(r, onPartial)
                prefs.bumpUsage(engine.name)
                remember(req, result)
                lastError = if (errors.isEmpty()) null else errors.joinToString(" / ")
                return result
            } catch (e: QuotaExceededException) {
                if (engine === gemini) prefs.markGeminiExhausted()
                errors += "${engine.name}: ${e.message}"
            } catch (e: EngineUnavailableException) {
                errors += "${engine.name}: ${e.message}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EngineChain", "${engine.name} failed", e)
                errors += "${engine.name}: ${e.javaClass.simpleName} ${e.message?.take(80)}"
            }
        }
        if (errors.isEmpty()) errors += "사용할 수 있는 API 키가 없습니다 (설정에서 Gemini 또는 Claude 키 입력)"
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
