package com.portfolio.screentranslator.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.portfolio.screentranslator.translate.SourceBlock
import com.portfolio.screentranslator.translate.TranslationResult

/**
 * Simple side-by-side panel: original text, engine A result, engine B result.
 * Lets the user judge translation quality on real pages before choosing a default engine.
 */
class CompareView(private val context: Context, private val wm: WindowManager) {
    private var root: View? = null

    fun show(blocks: List<SourceBlock>, a: Pair<String, TranslationResult?>, b: Pair<String, TranslationResult?>, errors: String?) {
        hide()
        val d = context.resources.displayMetrics.density
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 20, 20, 20))
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
        fun tv(text: String, bold: Boolean = false, color: Int = Color.WHITE) = TextView(context).apply {
            this.text = text; setTextColor(color); textSize = 13f
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }
        col.addView(tv("엔진 비교 (탭하면 닫힘)", bold = true, color = Color.YELLOW))
        if (!errors.isNullOrBlank()) col.addView(tv(errors, color = Color.rgb(255, 150, 150)))
        val mapA = a.second?.items?.filter { !it.isSfx }?.associateBy { it.sourceId } ?: emptyMap()
        val mapB = b.second?.items?.filter { !it.isSfx }?.associateBy { it.sourceId } ?: emptyMap()
        for (blk in blocks) {
            col.addView(tv("원문: " + blk.text.replace('\n', ' '), color = Color.LTGRAY))
            col.addView(tv("${a.first}: " + (mapA[blk.id]?.text ?: "—")))
            col.addView(tv("${b.first}: " + (mapB[blk.id]?.text ?: "—"), color = Color.rgb(170, 220, 255)))
            col.addView(View(context).apply { setBackgroundColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(-1, 1) })
        }
        val sfxA = a.second?.items?.filter { it.isSfx }?.joinToString(", ") { it.text } ?: ""
        val sfxB = b.second?.items?.filter { it.isSfx }?.joinToString(", ") { it.text } ?: ""
        if (sfxA.isNotEmpty() || sfxB.isNotEmpty()) {
            col.addView(tv("효과음 ${a.first}: $sfxA"))
            col.addView(tv("효과음 ${b.first}: $sfxB", color = Color.rgb(170, 220, 255)))
        }
        val scroll = ScrollView(context).apply { addView(col); setOnClickListener { hide() } }
        col.setOnClickListener { hide() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.7).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(scroll, lp)
        root = scroll
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}
