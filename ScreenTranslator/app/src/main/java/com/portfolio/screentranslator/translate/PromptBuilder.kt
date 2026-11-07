package com.portfolio.screentranslator.translate

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared prompt + response parsing for the LLM engines (Gemini, Gemma, Claude),
 * so switching engines never changes what the model is asked to do.
 */
object PromptBuilder {

    const val SYSTEM = """You translate text captured from a phone screen into natural Korean.
Rules:
- Output Korean only. Keep names, numbers, URLs and UI labels as they are unless a Korean form is standard.
- Translate each block as a whole; blocks are separate speech bubbles or UI elements, so keep them separate.
- Keep the register: casual speech stays casual (반말), polite stays polite (존댓말). Keep character names consistent with the history.
- OCR output may contain mistakes (wrong kana/kanji, split or merged lines). Infer the intended text from context before translating.
- EVERY block id in the input MUST appear in "blocks" with a non-empty "ko". Never skip or merge ids. Only if a block is clearly not text at all (random symbols from a drawing) return an empty string for it.
- When a screenshot is provided: (1) use it to fix OCR mistakes, (2) find onomatopoeia / sound effects drawn in the artwork that the OCR missed and add them as "sfx" entries with their approximate position, (3) keep SFX translations very short (e.g. 쾅, 두근, 스윽).
Respond with JSON only, matching this shape exactly:
{"blocks":[{"id":1,"ko":"..."}],"sfx":[{"ko":"...","box":[x,y,w,h]}]}
"box" values are fractions of the image size (0.0-1.0): x,y = top-left, w,h = size. Omit "sfx" if none."""

    fun userText(req: TranslationRequest): String {
        val sb = StringBuilder()
        val lang = when (req.sourceLangHint) {
            "ja" -> "Japanese"; "zh" -> "Chinese"; "en" -> "English"; else -> "Japanese, Chinese or English (detect)"
        }
        sb.append("Source language: ").append(lang).append('\n')
        if (req.history.isNotEmpty()) {
            sb.append("\nPrevious screens for context (source => Korean):\n")
            for ((src, ko) in req.history.takeLast(3)) {
                sb.append("- ").append(src.take(300).replace('\n', ' ')).append(" => ")
                    .append(ko.take(300).replace('\n', ' ')).append('\n')
            }
        }
        sb.append("\nBlocks to translate:\n")
        for (b in req.blocks) {
            sb.append("[id=").append(b.id).append("] ")
            if (b.vertical) sb.append("(vertical) ")
            sb.append(b.text.replace('\n', ' ')).append('\n')
        }
        if (req.blocks.isEmpty()) sb.append("(none recognised - only look for SFX in the image)\n")
        if (req.imageJpeg != null) sb.append("\nA screenshot of the screen is attached.")
        return sb.toString()
    }

    /** Tolerant parser: finds the first {...} in the text and reads blocks + sfx. */
    fun parse(raw: String): List<TranslatedText> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalStateException("No JSON in model reply: ${raw.take(120)}")
        val obj = JSONObject(raw.substring(start, end + 1))
        val out = ArrayList<TranslatedText>()
        val blocks: JSONArray? = obj.optJSONArray("blocks")
        if (blocks != null) for (i in 0 until blocks.length()) {
            val o = blocks.optJSONObject(i) ?: continue
            val ko = o.optString("ko", "").trim()
            if (ko.isEmpty()) continue
            out += TranslatedText(o.optInt("id", -1), ko, null, isSfx = false)
        }
        val sfx: JSONArray? = obj.optJSONArray("sfx")
        if (sfx != null) for (i in 0 until sfx.length()) {
            val o = sfx.optJSONObject(i) ?: continue
            val ko = o.optString("ko", "").trim()
            val box = o.optJSONArray("box") ?: continue
            if (ko.isEmpty() || box.length() < 4) continue
            val f = FloatArray(4) { box.optDouble(it, 0.0).toFloat().coerceIn(0f, 1f) }
            if (f[2] <= 0f || f[3] <= 0f) continue
            out += TranslatedText(null, ko, f, isSfx = true)
        }
        return out
    }
}
