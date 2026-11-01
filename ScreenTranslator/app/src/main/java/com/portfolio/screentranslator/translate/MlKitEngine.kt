package com.portfolio.screentranslator.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device fallback (same models as Google Translate offline). Lower quality, but works
 * with no network and no quota. Cannot see the image, so no SFX detection.
 */
class MlKitEngine : TranslationEngine {
    override val name = "mlkit"
    override val supportsImage = false

    private val translators = HashMap<String, Translator>()

    private fun translator(src: String): Translator = synchronized(translators) {
        translators.getOrPut(src) {
            val lang = when (src) {
                "ja" -> TranslateLanguage.JAPANESE
                "zh" -> TranslateLanguage.CHINESE
                else -> TranslateLanguage.ENGLISH
            }
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslateLanguage.KOREAN)
                    .build()
            )
        }
    }

    /** Download the three language packs up front (called from the settings screen). */
    suspend fun downloadAll(wifiOnly: Boolean) {
        val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        for (l in listOf("ja", "zh", "en")) translator(l).downloadModelIfNeeded(cond).await()
    }

    override suspend fun translate(req: TranslationRequest): TranslationResult {
        val out = ArrayList<TranslatedText>()
        for (b in req.blocks) {
            val src = if (req.sourceLangHint == "auto") LangDetect.guess(b.text) else req.sourceLangHint
            val t = translator(src)
            try {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                val ko = t.translate(b.text.replace('\n', ' ')).await()
                if (ko.isNotBlank()) out += TranslatedText(b.id, ko, null, isSfx = false)
            } catch (e: Exception) {
                throw EngineUnavailableException("ML Kit 번역 실패: ${e.message}")
            }
        }
        return TranslationResult(out, name)
    }
}

object LangDetect {
    /** Cheap script-based guess: kana => ja, han only => zh, otherwise en. */
    fun guess(text: String): String {
        var kana = 0; var han = 0; var latin = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x3040..0x30FF -> kana++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                ch.isLetter() && c < 0x0250 -> latin++
            }
        }
        return when {
            kana > 0 -> "ja"
            han > 0 && han >= latin -> "zh"
            else -> "en"
        }
    }
}
