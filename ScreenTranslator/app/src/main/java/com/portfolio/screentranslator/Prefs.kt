package com.portfolio.screentranslator

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId

/** All user settings + daily usage counters. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    var claudeKey: String
        get() = sp.getString("claude_key", "") ?: ""
        set(v) = sp.edit().putString("claude_key", v.trim()).apply()

    /** gemini | claude */
    var primaryEngine: String
        get() = (sp.getString("primary_engine", "gemini") ?: "gemini").let { if (it == "mlkit") "gemini" else it }
        set(v) = sp.edit().putString("primary_engine", v).apply()

    // Free-tier limits seen in AI Studio (2026-11): 3.5 Flash-Lite = 500 RPD, Flash models = 20 RPD.
    var geminiModel: String
        get() = sp.getString("gemini_model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
        set(v) = sp.edit().putString("gemini_model", v.trim()).apply()

    // Gemma 4 (14,400 RPD free). The 26B-A4B MoE variant answers several times faster than 31B
    // (31B took ~56 s per screen in testing); both ids are listed in Google's Gemma-on-Gemini docs.
    var gemmaModel: String
        get() = sp.getString("gemma_model2", "gemma-4-26b-a4b-it") ?: "gemma-4-26b-a4b-it"
        set(v) = sp.edit().putString("gemma_model2", v.trim()).apply()

    var claudeModel: String
        get() = sp.getString("claude_model", "claude-haiku-5-5") ?: "claude-haiku-5-5"
        set(v) = sp.edit().putString("claude_model", v.trim()).apply()

    /** ja | zh | en - only text in this script is translated. */
    var sourceLang: String
        get() = (sp.getString("source_lang", "ja") ?: "ja").let { if (it == "auto") "ja" else it }
        set(v) = sp.edit().putString("source_lang", v).apply()

    /** Sends the screenshot too (SFX detection, OCR fixes). Off by default: it is the
     *  single biggest contributor to translation latency. */
    var comicMode: Boolean
        get() = sp.getBoolean("comic_mode2", false)
        set(v) = sp.edit().putBoolean("comic_mode2", v).apply()

    /** Auto mode re-translates whenever the screen settles. Off by default: the normal flow
     *  is volume-down -> translate, touch -> dismiss, volume-down again on the next page. */
    var autoMode: Boolean
        get() = sp.getBoolean("auto_mode2", false)
        set(v) = sp.edit().putBoolean("auto_mode2", v).apply()

    /** Last status / error text, shown in the notification and on the main screen. */
    var lastStatus: String
        get() = sp.getString("last_status", "") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()

    /** box: opaque panel behind the translation. outline: no panel, thick halo around the glyphs. */
    var textStyle: String
        get() = sp.getString("text_style2", "outline") ?: "outline"
        set(v) = sp.edit().putString("text_style2", v).apply()

    /** Draw the OCR boxes (red) and erase areas (blue) to diagnose coverage problems. */
    var debugBoxes: Boolean
        get() = sp.getBoolean("debug_boxes", false)
        set(v) = sp.edit().putBoolean("debug_boxes", v).apply()

    var fontScale: Float
        get() = sp.getFloat("font_scale", 1.0f)
        set(v) = sp.edit().putFloat("font_scale", v.coerceIn(0.6f, 1.6f)).apply()

    // ---- daily usage counters (reset at Pacific midnight, matching Gemini's RPD reset) ----

    private fun todayKey(): String =
        LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

    fun usage(engine: String): Int {
        val day = sp.getString("usage_day", "")
        if (day != todayKey()) return 0
        return sp.getInt("usage_$engine", 0)
    }

    fun bumpUsage(engine: String) {
        val today = todayKey()
        val e = sp.edit()
        if (sp.getString("usage_day", "") != today) {
            e.putString("usage_day", today)
            for (k in listOf("gemini", "gemma", "claude")) e.putInt("usage_$k", 0)
        }
        e.putInt("usage_$engine", usage(engine) + 1).apply()
    }

    /** When Gemini returned 429 today we remember it so we stop hammering it until tomorrow. */
    var geminiExhaustedDay: String
        get() = sp.getString("gemini_exhausted", "") ?: ""
        set(v) = sp.edit().putString("gemini_exhausted", v).apply()

    val geminiExhaustedToday: Boolean get() = geminiExhaustedDay == todayKey()
    fun markGeminiExhausted() { geminiExhaustedDay = todayKey() }
}
