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
    }

    override fun onResume() {
        super.onResume()
        // 타일 등으로 바뀐 상태를 반영하려고 화면을 다시 그린다
        refresh()
    }

    private fun refresh() {
        val scrollY = (root.parent as? ScrollView)?.scrollY ?: 0
        root.removeAllViews()
        buildUi()
        updateStatus()
        (root.parent as? ScrollView)?.post { (root.parent as ScrollView).scrollTo(0, scrollY) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun apply() = FloatingService.instance?.applySettings()

    private fun updateStatus() {
        val on = FloatingService.instance != null
        statusText.text = if (on && prefs.appModeEnabled) "● 서비스 실행 중 — 앱 지정 모드: 선택한 앱에서만 버튼이 보입니다"
        else if (on) "● 서비스 실행 중 — 아래 버튼을 눌러 기능을 켜고 끄세요"
        else "○ 서비스 꺼짐 — 접근성 설정에서 「${getString(R.string.service_label)}」을 켜주세요"
        statusText.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val serviceOn = FloatingService.instance != null

        // ================= 최상단: 기능 켜기/끄기 (가장 크게) =================
        root.addView(TextView(this).apply {
            text = "플로팅 터치"; textSize = 15f; setTextColor(0xFF757575.toInt())
        })
        title("기능 켜기 / 끄기", 26f)
        statusText = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(10)) }
        root.addView(statusText)

        if (!serviceOn) {
            bigButton(
                "⚠  먼저 접근성 서비스를 켜주세요", "탭하면 접근성 설정으로 이동합니다 (최초 1회)",
                0xFFC62828.toInt(), true
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        featureToggle(Feature.SCROLL, "1", "빠른 넘김", "누를 때마다 한 페이지씩 넘김", 0xFF1E88E5.toInt())
        featureToggle(Feature.TAP, "2", "자동 터치", "지정한 위치를 반복 터치", 0xFFE53935.toInt())
        featureToggle(Feature.AUTO, "3", "자동 스크롤", "천천히 계속 스크롤 (속도 조절)", 0xFF8E24AA.toInt())
        bigButton("✕  모두 끄기", null, 0xFF616161.toInt(), false, heightDp = 56) {
            Controls.allOff(this); refresh()
        }
        hint("플로팅 버튼을 길게 누르면 이 설정 화면이 열립니다.")

        // ================= 앱 지정 모드 =================
        section("특정 앱에서만 자동으로 켜기")
        val apps = prefs.targetApps
        val appMode = prefs.appModeEnabled
        bigButton(
            "앱 지정 모드   ${if (appMode) "ON" else "OFF"}",
            if (appMode) "선택한 앱을 열면 자동으로 나타나고, 나가면 사라집니다"
            else "탭해서 켜기 · 켜면 아래 선택한 앱에서만 버튼이 보입니다",
            0xFF00897B.toInt(), appMode, heightDp = 76,
        ) {
            if (!appMode && apps.isEmpty()) {
                toast("먼저 앱을 선택하세요")
                pickApps()
            } else {
                prefs.appModeEnabled = !appMode
                apply(); refresh()
            }
        }
        root.addView(TextView(this).apply {
            textSize = 15f; setPadding(0, dp(2), 0, dp(4))
            text = if (apps.isEmpty()) "선택한 앱: 없음"
            else "선택한 앱: " + apps.map { appLabel(it) }.sorted().joinToString(", ")
        })
        button("앱 선택하기 (${apps.size}개 선택됨)") { pickApps() }
        hint("• 지정한 앱에 들어올 때마다 위에서 켜 둔 기능(1·2·3) 버튼이 자동으로 나타납니다.\n• 다른 앱이나 홈 화면으로 나가면 버튼이 사라지고, 실행 중이던 자동 터치·자동 스크롤도 멈춥니다.\n• 이 설정 화면을 열 때는 버튼이 그대로 보입니다(크기·투명도 확인용).")
        button("앱 감지 기록 보기 (문제 확인용)") {
            val logText = FloatingService.instance?.detectLog?.joinToString("\n")
                ?.ifEmpty { "아직 기록이 없습니다" } ?: "서비스가 꺼져 있습니다"
            android.app.AlertDialog.Builder(this)
                .setTitle("최근 감지 기록 (최신이 위)")
                .setMessage("현재 앱: ${FloatingService.instance?.foregroundPackage ?: "-"}\n\n$logText")
                .setPositiveButton("닫기", null)
                .show()
        }

        // ================= 아래: 세부 설정 =================
        section("사용법")
        hint(
            "• 플로팅 버튼은 드래그해서 옮길 수 있습니다.\n" +
                "• 화면 왼쪽/오른쪽 끝까지 끌어다 놓으면 엣지에 숨겨집니다. 숨겨진 버튼을 탭하면 다시 나타납니다.\n" +
                "• 서비스는 한 번만 켜두면 됩니다. 버튼을 치울 땐 서비스를 끄지 말고 위의 「모두 끄기」를 쓰세요.\n" +
                "• 빠른 설정(상단바)에 「플로팅 버튼」 타일을 추가하면 전체 보이기/숨기기도 됩니다."
        )

        // ======== 1. 빠른 스크롤 ========
        section("1. 빠른 스크롤 버튼 (파란색)")

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

        // ---- 스크롤 거리: 화면 높이의 5% ~ 90%, 0.5% 단위 (슬라이더 + 미세조절 버튼) ----
        val distLabel = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, 0) }
        val distSeek = SeekBar(this).apply {
            max = ((Prefs.MAX_SCROLL_PCT - Prefs.MIN_SCROLL_PCT) * 2).roundToInt()
        }
        fun pctToProgress(p: Float) = ((p - Prefs.MIN_SCROLL_PCT) * 2).roundToInt()
        fun setDist(pct: Float) {
            val v = pct.coerceIn(Prefs.MIN_SCROLL_PCT, Prefs.MAX_SCROLL_PCT)
            prefs.scrollDistancePct = v
            val px = (resources.displayMetrics.heightPixels * v / 100f).roundToInt()
            distLabel.text = "스크롤 거리: 화면 높이의 ${String.format(Locale.US, "%.1f", v)}% (약 ${px}px)"
            distSeek.progress = pctToProgress(v)
        }
        distSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setDist(Prefs.MIN_SCROLL_PCT + p / 2f)
            }
        })
        root.addView(distLabel)
        root.addView(distSeek)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(-5f, -0.5f, 0.5f, 5f).forEach { step ->
                addView(Button(context).apply {
                    text = if (step > 0) "+$step%" else "$step%"
                    isAllCaps = false
                    setOnClickListener { setDist(prefs.scrollDistancePct + step) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        setDist(prefs.scrollDistancePct)
        hint("웹툰 한 페이지만큼 넘어가도록 거리를 맞춰보세요. 앱이 터치 시작 후 약간의 거리(터치 슬롭)를 무시하므로 실제 이동량은 설정값보다 조금 작을 수 있습니다.")

        // ---- 관성(미끄러짐) 제거: 끝에서 멈춘 채 누르고 있는 시간 ----
        seekRow(
            label = {
                if (it == 0) "끝에서 멈춤 시간: 0ms (관성 허용 — 주루룩 미끄러짐)"
                else "끝에서 멈춤 시간: ${it * 10}ms (관성 제거)"
            },
            max = (Prefs.MAX_HOLD_MS / 10).toInt(),
            initial = (prefs.scrollHoldMs / 10).toInt(),
        ) { prefs.scrollHoldMs = it * 10L }
        hint("스와이프 끝 지점에서 손가락을 떼기 전에 이 시간만큼 멈춰 있습니다. 멈췄다 떼면 앱이 '속도 0'으로 판단해 미끄러지지 않습니다. 그래도 미끄러지면 값을 늘려보세요(권장 100~200ms).")

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
        // 1.0 ~ 10.0초, 0.1초 단위
        seekRow(
            label = { "터치 간격: ${String.format(Locale.US, "%.1f", it / 10.0 + 1.0)}초" },
            max = 90,
            initial = ((prefs.tapIntervalMs - 1000) / 100).toInt(),
        ) { prefs.tapIntervalMs = 1000L + it * 100L }
        styleRows(Prefs.KEY_TAP)

        title("조준점(⊕) 표시", 15f)
        hint("조준점 크기·투명도는 보이는 모양만 바뀝니다. 실제 터치는 항상 조준점의 정중앙 한 점에 들어갑니다. 크기를 바꿔도 중심 위치는 유지됩니다.")
        styleRows(Prefs.KEY_TARGET, "조준점 크기")

        // ======== 3. 자동 스크롤 ========
        section("3. 자동 스크롤 버튼 (보라색 ↓ / 실행 중 ■)")
        hint("↓ 버튼을 누르면 화면이 설정한 속도로 천천히 계속 아래로 스크롤됩니다. 다시 누르면 멈춥니다. 실행 중에 속도를 바꾸면 바로 반영됩니다.")
        seekRow(
            label = { v ->
                val dpPerSec = Prefs.MIN_AUTO_SPEED + v * 5
                val desc = when {
                    dpPerSec < 40 -> "아주 느림"
                    dpPerSec < 100 -> "느림"
                    dpPerSec < 250 -> "보통"
                    dpPerSec < 500 -> "빠름"
                    else -> "아주 빠름"
                }
                "스크롤 속도: ${dpPerSec}dp/초 ($desc, 약 ${(dpPerSec * resources.displayMetrics.density).roundToInt()}px/초)"
            },
            max = (Prefs.MAX_AUTO_SPEED - Prefs.MIN_AUTO_SPEED) / 5,
            initial = (prefs.autoSpeedDp - Prefs.MIN_AUTO_SPEED) / 5,
        ) { prefs.autoSpeedDp = Prefs.MIN_AUTO_SPEED + it * 5 }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(-10, -5, 5, 10).forEach { step ->
                addView(Button(context).apply {
                    text = if (step > 0) "+$step" else "$step"
                    setOnClickListener { prefs.autoSpeedDp = prefs.autoSpeedDp + step; refresh() }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        hint("화면을 직접 만지거나, 1번·2번 기능이 동작하면 자동 스크롤이 잠깐(1초) 멈췄다가 다시 이어집니다. 화면 아래쪽 80% 지점부터 위쪽 20% 지점까지 끌어올린 뒤, 손을 뗐다가 다시 아래에서 시작하므로 그 순간 아주 짧게 멈칫할 수 있습니다.")
        styleRows(Prefs.KEY_AUTO)

        // ======== 기타 ========
        section("기타")
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
                ?: listOf(Prefs.KEY_SCROLL, Prefs.KEY_TAP, Prefs.KEY_TARGET, Prefs.KEY_AUTO)
                    .forEach(prefs::resetPosition)
        }

        hint(
            "\n※ 참고: 0.001초처럼 아주 짧은 스와이프는 앱마다 다르게 해석될 수 있습니다 " +
                "(일부 앱은 아주 빠른 플링으로, 일부 앱은 무시하거나 탭으로 처리). " +
                "원하는 대로 동작하지 않으면 시간을 조금 늘려보세요.\n" +
                "※ 게임/서비스에 따라 자동 터치가 이용약관 위반일 수 있습니다."
        )
    }

    /** 크기 / 투명도 슬라이더 */
    private fun styleRows(key: String, sizeTitle: String = "버튼 크기") {
        val min = Prefs.minSize(key)
        seekRow(
            label = { "$sizeTitle: ${it + min}dp" },
            max = Prefs.MAX_SIZE_DP - min,
            initial = prefs.sizeDp(key) - min,
        ) { prefs.setSizeDp(key, it + min); apply() }
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

    /** 큰 기능 토글 카드: 켜짐이면 기능 색으로 채움, 꺼짐이면 흰 바탕 + 색 테두리 */
    private fun featureToggle(f: Feature, num: String, name: String, desc: String, color: Int) {
        val on = Controls.isOn(prefs, f)
        bigButton(
            "$num   $name   ${if (on) "ON" else "OFF"}",
            if (on) "켜짐 · 탭해서 끄기" else "$desc · 탭해서 켜기",
            color, on,
        ) {
            Controls.toggle(this, f)
            if (FloatingService.instance == null) toast("접근성 서비스를 켜야 화면에 버튼이 나타납니다")
            refresh()
        }
    }

    private fun bigButton(
        title: String, sub: String?, color: Int, filled: Boolean,
        heightDp: Int = 84, onClick: () -> Unit,
    ) {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (filled) color else Color.WHITE)
            setStroke(dp(if (filled) 0 else 2), color)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dp(20), dp(8), dp(20), dp(8))
            isClickable = true
            isFocusable = true
            foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                .let { a -> a.getDrawable(0).also { a.recycle() } }
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (filled) Color.WHITE else color)
            })
            if (sub != null) addView(TextView(context).apply {
                text = sub; textSize = 13f
                setTextColor(if (filled) 0xDDFFFFFF.toInt() else 0xFF616161.toInt())
            })
        }
        root.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp)).apply { bottomMargin = dp(10) })
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 설치된 앱(런처에 보이는 앱) 목록에서 여러 개 선택 */
    private fun pickApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val selected = prefs.targetApps.toMutableSet()
        val all = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .map { it to appLabel(it) }
            // 선택된 앱을 위에, 나머지는 이름순
            .sortedWith(compareBy<Pair<String, String>>({ it.first !in selected }, { it.second.lowercase() }))
        val labels = all.map { it.second }.toTypedArray()
        val checked = all.map { it.first in selected }.toBooleanArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("버튼을 보여줄 앱 선택")
            .setMultiChoiceItems(labels, checked) { _, i, isChecked ->
                if (isChecked) selected += all[i].first else selected -= all[i].first
            }
            .setPositiveButton("저장") { _, _ ->
                prefs.targetApps = selected
                if (selected.isNotEmpty() && !prefs.appModeEnabled) {
                    prefs.appModeEnabled = true
                    toast("앱 지정 모드를 켰습니다")
                }
                if (selected.isEmpty()) prefs.appModeEnabled = false
                apply(); refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar) = Unit
        override fun onStopTrackingTouch(sb: SeekBar) = Unit
    }
}
