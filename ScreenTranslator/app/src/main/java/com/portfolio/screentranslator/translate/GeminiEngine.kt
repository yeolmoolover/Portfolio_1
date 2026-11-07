package com.portfolio.screentranslator.translate

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini API (generateContent). Also used for Gemma models, which are served from the
 * same endpoint. Free-tier quota errors arrive as HTTP 429 and are mapped to
 * [QuotaExceededException] so the chain can fall through to the next engine.
 */
class GeminiEngine(
    override val name: String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    /** Gemma models may not accept thinking config / images; keep the request minimal. */
    private val isGemma: Boolean = false,
) : TranslationEngine {

    override val supportsImage: Boolean get() = true

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(if (isGemma) 25 else 40, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(req: TranslationRequest, onPartial: ((TranslatedText) -> Unit)?): TranslationResult = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isBlank()) throw EngineUnavailableException("Gemini API 키가 없습니다")
        val model = modelProvider()

        val parts = JSONArray()
        parts.put(JSONObject().put("text", PromptBuilder.userText(req)))
        if (req.imageJpeg != null) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(req.imageJpeg, Base64.NO_WRAP))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PromptBuilder.SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val base = "https://generativelanguage.googleapis.com/v1beta/models/$model"
        val parser = onPartial?.let { PromptBuilder.IncrementalParser(it) }

        // Streamed request (bubbles can be drawn as they arrive). Some models reject
        // thinkingConfig; on that specific 400 retry once without it.
        var r = callStream(base, key, body, withThinkingConfig = !isGemma) { parser?.feed(it) }
        if (r.code == 400 && !isGemma && r.error.contains("thinking", ignoreCase = true)) {
            r = callStream(base, key, body, withThinkingConfig = false) { parser?.feed(it) }
        }
        when {
            r.code == 429 -> throw QuotaExceededException("$name 한도 초과 (429)")
            r.code !in 200..299 -> throw EngineUnavailableException("$name HTTP ${r.code}: ${errorMessage(r.error)}")
            r.text.isBlank() -> throw EngineUnavailableException("$name: 빈 응답 (${r.error.ifBlank { "unknown" }})")
        }
        try {
            TranslationResult(PromptBuilder.parse(r.text), name)
        } catch (e: Exception) {
            // Malformed reply: one non-streaming retry before giving up on this engine.
            val (code2, payload2) = call("$base:generateContent", key, body, withThinkingConfig = !isGemma)
            if (code2 !in 200..299) throw EngineUnavailableException("$name 재시도 HTTP $code2: ${errorMessage(payload2)}")
            val text2 = JSONObject(payload2).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                    buildString { for (i in 0 until parts.length()) { val p = parts.optJSONObject(i) ?: continue; if (!p.optBoolean("thought", false)) append(p.optString("text", "")) } }
                } ?: ""
            TranslationResult(PromptBuilder.parse(text2), name)
        }
    }

    private class StreamResult(val code: Int, val text: String, val error: String)

    /** POST :streamGenerateContent?alt=sse and concatenate the text parts, calling [onText] per chunk. */
    private fun callStream(base: String, key: String, body: JSONObject, withThinkingConfig: Boolean, onText: (String) -> Unit): StreamResult {
        val request = Request.Builder()
            .url("$base:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(buildBody(body, withThinkingConfig).toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return StreamResult(resp.code, "", resp.body?.string() ?: "")
            val sb = StringBuilder(); var blockReason = ""
            val source = resp.body?.source() ?: return StreamResult(resp.code, "", "no body")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val json = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
                json.optJSONObject("promptFeedback")?.optString("blockReason", "")?.let { if (it.isNotEmpty()) blockReason = it }
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    val t = p.optString("text", "")
                    if (t.isNotEmpty()) { sb.append(t); onText(t) }
                }
            }
            return StreamResult(resp.code, sb.toString(), blockReason)
        }
    }

    private fun errorMessage(payload: String): String = runCatching {
        JSONObject(payload).getJSONObject("error").let { e ->
            "${e.optString("status")} ${e.optString("message")}".trim()
        }
    }.getOrDefault(payload.replace('\n', ' ')).take(220)

    private fun buildBody(base: JSONObject, withThinkingConfig: Boolean): JSONObject {
        val gen = JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 1024)
        if (!isGemma) {
            gen.put("responseMimeType", "application/json")
            // Schema-constrained output: the model cannot emit malformed JSON.
            gen.put("responseSchema", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("blocks", JSONObject().put("type", "ARRAY").put("items", JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", JSONObject()
                            .put("id", JSONObject().put("type", "INTEGER"))
                            .put("ko", JSONObject().put("type", "STRING")))
                        .put("required", JSONArray().put("id").put("ko"))))
                    .put("sfx", JSONObject().put("type", "ARRAY").put("items", JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", JSONObject()
                            .put("ko", JSONObject().put("type", "STRING"))
                            .put("box", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "NUMBER"))))
                        .put("required", JSONArray().put("ko").put("box")))))
                .put("required", JSONArray().put("blocks")))
        }
        if (withThinkingConfig) {
            // Gemini 3.x: thinking cannot be turned off; "minimal" is the cheapest level.
            // Gemini 2.5: thinkingBudget 0 turns it off. (thinkingBudget is rejected by 3.x.)
            val model = modelProvider()
            val cfg = if (model.startsWith("gemini-3")) JSONObject().put("thinkingLevel", "minimal")
                      else JSONObject().put("thinkingBudget", 0)
            gen.put("thinkingConfig", cfg)
        }
        return JSONObject(base.toString()).put("generationConfig", gen)
    }

    /** Returns (http status, body). */
    private fun call(url: String, key: String, base: JSONObject, withThinkingConfig: Boolean): Pair<Int, String> {
        val body = buildBody(base, withThinkingConfig)
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }
}
