package com.portfolio.floatingtouch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 설정 화면. 값을 바꾸면 즉시 저장되고, 서비스가 켜져 있으면 바로 반영된다. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun apply() = FloatingService.instance?.applySettings()

    private fun updateStatus() {
        val on = FloatingService.instance != null
        statusText.text = if (on) "● 서비스 실행 중 — 플로팅 버튼이 화면에 표시됩니다"
        else "○ 서비스 꺼짐 — 아래 '접근성 설정 열기'에서 「${getString(R.string.service_label)}」을 켜주세요"
        statusText.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        title("플로팅 터치", 22f)
        statusText = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(statusText)

        button("접근성 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("앱 정보 열기 (Android 13+ '제한된 설정 허용' 필요 시)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        button("버튼 위치 초기화") {
            FloatingService.instance?.resetPositions()
                ?: run {
                    listOf(Prefs.KEY_SCROLL, Prefs.KEY_TAP, Prefs.KEY_TARGET).forEach(prefs::resetPosition)
                }
        }
        button("서비스 끄기 (모든 플로팅 버튼 제거)") {
            FloatingService.instance?.disableSelf()
            statusText.postDelayed({ updateStatus() }, 300)
        }

        hint(
            "• 버튼을 드래그해서 옮길 수 있습니다.\n" +
                "• 버튼을 화면 왼쪽/오른쪽 끝까지 끌어다 놓으면 엣지에 숨겨집니다. 숨겨진 버튼을 탭하면 다시 나타납니다."
        )

        // ======== 1. 빠른 스크롤 ========
        section("1. 빠른 스크롤 버튼 (파란색)")
        switchRow("버튼 표시", prefs.scrollEnabled) { prefs.scrollEnabled = it; apply() }

        val durLabel = TextView(this).apply { textSize = 15f }
        val durInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "초 단위 직접 입력 (0.001 ~ 10)"
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        // 0.001초~10초는 범위가 10,000배라 로그 스케일 슬라이더 사용
        val durSeek = SeekBar(this).apply { max = 1000 }
        fun showDur(ms: Long) {
            durLabel.text = "스크롤 동작 시간: ${fmtSec(ms)}초 (${ms}ms)"
        }
        durSeek.progress = (log10(prefs.scrollDurationMs.toDouble()) * 250).roundToInt()
        showDur(prefs.scrollDurationMs)
        durSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ms = 10.0.pow(p / 250.0).roundToLong()
                    .coerceIn(Prefs.MIN_SCROLL_MS, Prefs.MAX_SCROLL_MS)
                prefs.scrollDurationMs = ms
                showDur(ms)
            }
        })
        val applyDurInput = {
            val sec = durInput.text.toString().toDoubleOrNull()
            if (sec == null) {
                toast("숫자를 입력하세요")
            } else {
                val ms = (sec * 1000).roundToLong().coerceIn(Prefs.MIN_SCROLL_MS, Prefs.MAX_SCROLL_MS)
                prefs.scrollDurationMs = ms
                durSeek.progress = (log10(ms.toDouble()) * 250).roundToInt()
                showDur(ms)
                durInput.setText("")
            }
        }
        durInput.setOnEditorActionListener { _, _, _ -> applyDurInput(); true }
        root.addView(durLabel)
        root.addView(durSeek)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(durInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(context).apply { text = "적용"; setOnClickListener { applyDurInput() } })
        })

        root.addView(TextView(this).apply { text = "스크롤 방향"; textSize = 15f; setPadding(0, dp(8), 0, 0) })
        root.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val down = RadioButton(context).apply { text = "▼ 아래 내용 보기"; id = View.generateViewId() }
            val up = RadioButton(context).apply { text = "▲ 위 내용 보기"; id = View.generateViewId() }
            addView(down); addView(up)
            check(if (prefs.scrollDown) down.id else up.id)
            setOnCheckedChangeListener { _, checked -> prefs.scrollDown = checked == down.id; apply() }
        })
        styleRows(Prefs.KEY_SCROLL)

        // ======== 2. 자동 반복 터치 ========
        section("2. 자동 반복 터치 버튼 (빨간색 ▶ / 실행 중 초록색 ■)")
        hint("빨간 조준점(⊕)을 원하는 위치로 끌어다 놓고 ▶ 버튼을 누르면 그 위치를 반복 터치합니다. 다시 누르면 정지합니다. 실행 중에는 조준점을 옮길 수 없습니다.")
        switchRow("버튼 표시", prefs.tapEnabled) { prefs.tapEnabled = it; apply() }
        // 1.0 ~ 10.0초, 0.1초 단위
        seekRow(
            label = { "터치 간격: ${String.format(Locale.US, "%.1f", it / 10.0 + 1.0)}초" },
            max = 90,
            initial = ((prefs.tapIntervalMs - 1000) / 100).toInt(),
        ) { prefs.tapIntervalMs = 1000L + it * 100L }
        styleRows(Prefs.KEY_TAP)

        hint(
            "\n※ 참고: 0.001초처럼 아주 짧은 스와이프는 앱마다 다르게 해석될 수 있습니다 " +
                "(일부 앱은 아주 빠른 플링으로, 일부 앱은 무시하거나 탭으로 처리). " +
                "원하는 대로 동작하지 않으면 시간을 조금 늘려보세요.\n" +
                "※ 게임/서비스에 따라 자동 터치가 이용약관 위반일 수 있습니다."
        )
    }

    /** 크기 / 투명도 슬라이더 */
    private fun styleRows(key: String) {
        seekRow(
            label = { "버튼 크기: ${it + Prefs.MIN_SIZE_DP}dp" },
            max = Prefs.MAX_SIZE_DP - Prefs.MIN_SIZE_DP,
            initial = prefs.sizeDp(key) - Prefs.MIN_SIZE_DP,
        ) { prefs.setSizeDp(key, it + Prefs.MIN_SIZE_DP); apply() }
        seekRow(
            label = { "불투명도: ${it + 20}%" },
            max = 80,
            initial = (prefs.alpha(key) * 100).roundToInt() - 20,
        ) { prefs.setAlpha(key, (it + 20) / 100f); apply() }
    }

    // ---------------------------------------------------------------- helpers

    private fun fmtSec(ms: Long): String =
        String.format(Locale.US, "%.3f", ms / 1000.0).trimEnd('0').trimEnd('.')

    private fun title(text: String, size: Float) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = size; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        })
    }

    private fun section(text: String) {
        root.addView(View(this).apply { setBackgroundColor(0x22000000) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(20); bottomMargin = dp(12) })
        title(text, 17f)
    }

    private fun hint(text: String) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(0xFF616161.toInt())
            setPadding(0, dp(6), 0, dp(6))
        })
    }

    private fun button(text: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    @Suppress("DEPRECATION")
    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            this.text = text; textSize = 15f; isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun seekRow(label: (Int) -> String, max: Int, initial: Int, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply {
            textSize = 15f; text = label(initial.coerceIn(0, max)); setPadding(0, dp(8), 0, 0)
        }
        val sb = SeekBar(this).apply {
            this.max = max
            progress = initial.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SimpleSeek() {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    tv.text = label(p)
                    if (fromUser) onChange(p)
                }
            })
        }
        root.addView(tv)
        root.addView(sb)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar) = Unit
        override fun onStopTrackingTouch(sb: SeekBar) = Unit
    }
}
