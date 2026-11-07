package com.portfolio.screentranslator.translate

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigDisabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Claude via the official Java SDK. Default model is Haiku 5.5 (cheapest); the user can
 * switch to Sonnet/Opus in settings. Effort is forced LOW: translation of a single screen
 * does not need long reasoning, and thinking tokens are billed as output.
 */
class ClaudeEngine(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
) : TranslationEngine {

    override val name = "claude"
    override val supportsImage = true

    @Volatile private var cachedKey: String? = null
    @Volatile private var client: AnthropicClient? = null

    private fun client(key: String): AnthropicClient {
        val c = client
        if (c != null && cachedKey == key) return c
        val fresh = AnthropicOkHttpClient.builder().apiKey(key).build()
        client = fresh; cachedKey = key
        return fresh
    }

    override suspend fun translate(req: TranslationRequest, onPartial: ((TranslatedText) -> Unit)?): TranslationResult = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isBlank()) throw EngineUnavailableException("Claude API 키가 없습니다")

        val content = ArrayList<ContentBlockParam>()
        if (req.imageJpeg != null) {
            content += ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(Base64.encodeToString(req.imageJpeg, Base64.NO_WRAP))
                        .build())
                    .build()
            )
        }
        content += ContentBlockParam.ofText(TextBlockParam.builder().text(PromptBuilder.userText(req)).build())

        val model = modelProvider()
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(1024L)
            .system(PromptBuilder.SYSTEM)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessageOfContentBlockParams(content)
        // Haiku 5.5 accepts thinking "disabled" at effort <= high; Sonnet/Opus 5.5 reject it
        // (400), so only send it for Haiku. Saves the thinking tokens' latency on a short job.
        if (model.startsWith("claude-haiku")) builder.thinking(ThinkingConfigDisabled.builder().build())
        val params = builder.build()

        // Stream so bubbles can be drawn as they complete.
        val parser = onPartial?.let { PromptBuilder.IncrementalParser(it) }
        val sb = StringBuilder()
        var refusal = false
        try {
            val stream = client(key).messages().createStreaming(params)
            try {
                stream.stream().forEach { ev ->
                    ev.contentBlockDelta().ifPresent { d ->
                        d.delta().text().ifPresent { t -> sb.append(t.text()); parser?.feed(t.text()) }
                    }
                    ev.messageDelta().ifPresent { d ->
                        if (d.delta().stopReason().orElse(null) == StopReason.REFUSAL) refusal = true
                    }
                }
            } finally { stream.close() }
        } catch (e: RateLimitException) {
            throw QuotaExceededException("Claude 요청 한도 초과 (429)")
        } catch (e: AnthropicServiceException) {
            throw EngineUnavailableException("Claude HTTP ${e.statusCode()}: ${e.message?.take(200)}")
        } catch (e: AnthropicIoException) {
            throw EngineUnavailableException("Claude 네트워크 오류: ${e.message}")
        } catch (e: AnthropicInvalidDataException) {
            throw EngineUnavailableException("Claude 응답 해석 실패: ${e.message}")
        }

        if (refusal) throw EngineUnavailableException("Claude가 이 화면의 번역을 거부했습니다")
        TranslationResult(PromptBuilder.parse(sb.toString()), name)
    }
}
