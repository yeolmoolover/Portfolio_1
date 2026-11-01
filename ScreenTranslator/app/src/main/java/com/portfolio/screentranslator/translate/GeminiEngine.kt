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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(req: TranslationRequest): TranslationResult = withContext(Dispatchers.IO) {
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        // First try with thinking disabled (saves output tokens). Some models reject
        // thinkingConfig; on that specific 400 we retry once without it.
        var text = call(url, key, body, withThinkingConfig = !isGemma)
        if (text.first == 400 && !isGemma && text.second.contains("thinking", ignoreCase = true)) {
            text = call(url, key, body, withThinkingConfig = false)
        }
        val (code, payload) = text
        when {
            code == 429 -> throw QuotaExceededException("$name 한도 초과 (429)")
            code !in 200..299 -> throw EngineUnavailableException("$name HTTP $code: ${errorMessage(payload)}")
        }
        val json = JSONObject(payload)
        val candidates = json.optJSONArray("candidates")
            ?: throw EngineUnavailableException("$name: 응답에 candidates 없음 (${json.optJSONObject("promptFeedback")?.optString("blockReason") ?: "unknown"})")
        val sb = StringBuilder()
        val first = candidates.optJSONObject(0)
        val cParts = first?.optJSONObject("content")?.optJSONArray("parts")
        if (cParts != null) for (i in 0 until cParts.length()) {
            val p = cParts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        TranslationResult(PromptBuilder.parse(sb.toString()), name)
    }

    private fun errorMessage(payload: String): String = runCatching {
        JSONObject(payload).getJSONObject("error").let { e ->
            "${e.optString("status")} ${e.optString("message")}".trim()
        }
    }.getOrDefault(payload.replace('\n', ' ')).take(220)

    /** Returns (http status, body). */
    private fun call(url: String, key: String, base: JSONObject, withThinkingConfig: Boolean): Pair<Int, String> {
        val gen = JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 2048)
        if (!isGemma) gen.put("responseMimeType", "application/json")
        if (withThinkingConfig) {
            // Gemini 3.x: thinking cannot be turned off; "minimal" is the cheapest level.
            // Gemini 2.5: thinkingBudget 0 turns it off. (thinkingBudget is rejected by 3.x.)
            val model = modelProvider()
            val cfg = if (model.startsWith("gemini-3")) JSONObject().put("thinkingLevel", "minimal")
                      else JSONObject().put("thinkingBudget", 0)
            gen.put("thinkingConfig", cfg)
        }
        val body = JSONObject(base.toString()).put("generationConfig", gen)
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
