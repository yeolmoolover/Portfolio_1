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

    /** On-disk copy of the cache so pages seen before never hit the network again. */
    private val cacheFile: File? = context?.let { File(it.filesDir, "translation_cache.json") }

    val gemini = GeminiEngine("gemini", { prefs.geminiKey }, { prefs.geminiModel })
    val gemma = GeminiEngine("gemma", { prefs.geminiKey }, { prefs.gemmaModel }, isGemma = true)
    val claude = ClaudeEngine({ prefs.claudeKey }, { prefs.claudeModel })

    private val cache = object : LinkedHashMap<String, TranslationResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResult>?) = size > 3000
    }
    @Volatile private var dirty = false

    init { loadCache() }

    private fun loadCache() {
        val f = cacheFile ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            synchronized(cache) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val items = o.getJSONArray("items")
                    val list = ArrayList<TranslatedText>()
                    for (j in 0 until items.length()) {
                        val it = items.getJSONObject(j)
                        val box = it.optJSONArray("box")?.let { b -> FloatArray(b.length()) { k -> b.optDouble(k).toFloat() } }
                        list += TranslatedText(if (it.has("id")) it.getInt("id") else null, it.getString("ko"), box, it.optBoolean("sfx", false))
                    }
                    cache[o.getString("key")] = TranslationResult(list, o.optString("engine", "cache"))
                }
            }
        }.onFailure { Log.w("EngineChain", "cache load failed", it) }
    }

    /** Writes the cache to disk (called after each new translation; cheap at this size). */
    fun saveCache() {
        val f = cacheFile ?: return
        if (!dirty) return
        val arr = JSONArray()
        synchronized(cache) {
            for ((k, v) in cache) {
                val items = JSONArray()
                for (t in v.items) {
                    val o = JSONObject().put("ko", t.text).put("sfx", t.isSfx)
                    t.sourceId?.let { o.put("id", it) }
                    t.boxNorm?.let { b -> o.put("box", JSONArray().apply { b.forEach { put(it.toDouble()) } }) }
                    items.put(o)
                }
                arr.put(JSONObject().put("key", k).put("engine", v.engineUsed).put("items", items))
            }
        }
        runCatching { File(f.path + ".tmp").apply { writeText(arr.toString()) }.renameTo(f) }
            .onFailure { Log.w("EngineChain", "cache save failed", it) }
        dirty = false
    }

    fun cacheSize(): Int = synchronized(cache) { cache.size }

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

    fun cacheKey(req: TranslationRequest, comic: Boolean): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(if (comic) 1 else 0)
        for (b in req.blocks) md.update(b.text.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun cached(key: String): TranslationResult? = synchronized(cache) { cache[key] }

    fun historySnapshot(): List<Pair<String, String>> = synchronized(history) { history.toList() }

    /** Runs the chain. Throws only when every engine failed. */
    suspend fun translate(req: TranslationRequest, cacheKey: String, onPartial: ((TranslatedText) -> Unit)? = null): TranslationResult {
        cached(cacheKey)?.let { return it }
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
                synchronized(cache) { cache[cacheKey] = result }
                dirty = true
                saveCache()
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
