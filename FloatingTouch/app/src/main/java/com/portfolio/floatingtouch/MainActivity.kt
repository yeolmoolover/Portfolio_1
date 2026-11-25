package com.portfolio.floatingtouch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

/**
 * 설정 화면. 값을 바꾸면 즉시 저장되고, 서비스가 켜져 있으면 바로 반영된다.
 * 레이아웃 XML 없이 코드로 그린다 (카드형, 라이트/다크 모드 대응).
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var c: Palette

    /** 라이트/다크 모드 색 */
    private class Palette(night: Boolean) {
        val bg = if (night) 0xFF0F0F10.toInt() else 0xFFF2F3F5.toInt()
        val card = if (night) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
        val text = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        val sub = if (night) 0xFF9A9AA1.toInt() else 0xFF6B6F76.toInt()
        val divider = if (night) 0xFF2C2C2E.toInt() else 0xFFE8E9EC.toInt()
        val accent = if (night) 0xFF8C9BFF.toInt() else 0xFF3D5AFE.toInt()
        val off = if (night) 0xFF48484A.toInt() else 0xFFC7CACF.toInt()
        val chip = if (night) 0xFF2C2C2E.toInt() else 0xFFF0F1F4.toInt()
        val ripple = if (night) 0x33FFFFFF else 0x1F000000
        val good = if (night) 0xFF4ADE80.toInt() else 0xFF15A34A.toInt()
        val bad = if (night) 0xFFF87171.toInt() else 0xFFDC2626.toInt()
    }

    private val colScroll = 0xFF3D7BEA.toInt()
    private val colTap = 0xFFE5484D.toInt()
    private val colAuto = 0xFF8E4EC6.toInt()
    private val colApp = 0xFF12A594.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        c = Palette(MenuTheme.isNight(this))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(c.bg)
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // 메뉴 버튼·타일 등으로 바뀐 상태를 반영하려고 화면을 다시 그린다
        refresh()
    }

    private fun refresh() {
        val y = scroll.scrollY
        root.removeAllViews()
        buildUi()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun apply() = FloatingService.instance?.applySettings()

    // ================================================================ 화면 구성

    private fun buildUi() {
        val serviceOn = FloatingService.instance != null

        // ---- 헤더 ----
        root.addView(TextView(this).apply {
            text = "플로팅 터치"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.text)
        })
        root.addView(statusPill(serviceOn), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        })

        if (!serviceOn) {
            val card = card(topMargin = 16)
            card.addView(TextView(this).apply {
                text = "접근성 서비스를 켜주세요"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c.text); setPadding(dp(18), dp(16), dp(18), 0)
            })
            card.addView(TextView(this).apply {
                text = "플로팅 버튼을 띄우고 스크롤·터치를 하려면 처음 한 번 켜야 합니다. " +
                    "켜지지 않으면 아래 「앱 정보」에서 ⋮ → 제한된 설정 허용 후 다시 시도하세요."
                textSize = 14f; setTextColor(c.sub); setPadding(dp(18), dp(6), dp(18), dp(12))
            })
            card.addView(primaryButton("접근성 설정 열기") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply {
                setMargins(dp(18), 0, dp(18), dp(18))
            })
        }

        // ---- 기능 ----
        sectionLabel("기능")
        val features = card()
        featureRow(features, Feature.SCROLL, R.drawable.ic_page_down, "빠른 넘김", "누를 때마다 한 페이지씩", colScroll)
        divider(features)
        featureRow(features, Feature.TAP, R.drawable.ic_auto_tap, "자동 터치", "지정한 위치를 반복 터치", colTap)
        stepperRow(features, "터치 간격", "${fmt1(prefs.tapIntervalMs / 1000.0)}초",
            listOf("−1" to -1000L, "−0.1" to -100L), listOf("+0.1" to 100L, "+1" to 1000L),
        ) { prefs.tapIntervalMs = prefs.tapIntervalMs + it }
        divider(features)
        featureRow(features, Feature.AUTO, R.drawable.ic_auto_scroll, "자동 스크롤", "천천히 계속 스크롤", colAuto)
        stepperRow(features, "스크롤 속도", "${prefs.autoSpeedDp}dp/초",
            listOf("−10" to -10L, "−5" to -5L), listOf("+5" to 5L, "+10" to 10L),
        ) { prefs.autoSpeedDp = prefs.autoSpeedDp + it.toInt() }
        divider(features)
        textAction(features, "모두 끄기", c.bad) { Controls.allOff(this); refresh() }
        footnote("플로팅 버튼을 길게 누르면 이 화면이 열립니다.")

        // ---- 앱 지정 모드 ----
        sectionLabel("특정 앱에서만 켜기")
        val apps = prefs.targetApps
        val appCard = card()
        switchRow(
            appCard, R.drawable.ic_menu, colApp, "앱 지정 모드",
            if (prefs.appModeEnabled) "선택한 앱에서만 버튼이 나타납니다" else "꺼짐 · 모든 앱에서 버튼 표시",
            prefs.appModeEnabled,
        ) {
            if (!prefs.appModeEnabled && apps.isEmpty()) {
                toast("먼저 앱을 선택하세요"); pickApps()
            } else {
                prefs.appModeEnabled = !prefs.appModeEnabled; apply(); refresh()
            }
        }
        divider(appCard)
        navRow(
            appCard, "선택한 앱",
            if (apps.isEmpty()) "없음 · 탭해서 선택" else apps.map { appLabel(it) }.sorted().joinToString(", "),
        ) { pickApps() }
        divider(appCard)
        styleSliders(appCard, Prefs.KEY_MINI, "메뉴(≡) 버튼 크기")
        divider(appCard)
        navRow(appCard, "앱 감지 기록", "버튼이 갑자기 사라질 때 확인용") { showDetectLog() }
        footnote(
            "지정한 앱에서는 작은 메뉴(≡) 버튼이 보입니다. 누르면 기능 버튼이 튀어나와 앱으로 오지 않고 바로 켜고 끌 수 있어요. " +
                "다른 앱이나 홈으로 나가면 모두 사라지고, 실행 중이던 자동 터치·스크롤도 멈춥니다."
        )

        // ---- 1. 빠른 넘김 ----
        sectionLabel("빠른 넘김 설정")
        val sc = card()
        logDurationSlider(sc)
        divider(sc)
        sliderRow(
            sc, "스크롤 거리",
            { "화면의 ${fmt1((Prefs.MIN_SCROLL_PCT + it / 2f).toDouble())}%" },
            ((Prefs.MAX_SCROLL_PCT - Prefs.MIN_SCROLL_PCT) * 2).roundToInt(),
            ((prefs.scrollDistancePct - Prefs.MIN_SCROLL_PCT) * 2).roundToInt(),
        ) { prefs.scrollDistancePct = Prefs.MIN_SCROLL_PCT + it / 2f }
        stepperRow(sc, "미세 조정", "${fmt1(prefs.scrollDistancePct.toDouble())}%",
            listOf("−5" to -50L, "−0.5" to -5L), listOf("+0.5" to 5L, "+5" to 50L),
        ) { prefs.scrollDistancePct = prefs.scrollDistancePct + it / 10f }
        divider(sc)
        sliderRow(
            sc, "끝에서 멈춤", { if (it == 0) "0ms · 미끄러짐 허용" else "${it * 10}ms" },
            (Prefs.MAX_HOLD_MS / 10).toInt(), (prefs.scrollHoldMs / 10).toInt(),
        ) { prefs.scrollHoldMs = it * 10L }
        divider(sc)
        segmentedRow(sc, "방향", listOf("아래 내용 보기", "위 내용 보기"), if (prefs.scrollDown) 0 else 1) {
            prefs.scrollDown = it == 0; apply()
        }
        divider(sc)
        styleSliders(sc, Prefs.KEY_SCROLL)
        footnote(
            "동작 시간은 0.05초 이상을 권장해요. 너무 짧으면 일부 앱(브라우저 등)에서 스크롤로 인식하지 못할 수 있습니다. " +
                "끝에서 멈춤은 손을 떼기 전에 잠깐 멈춰 주르륵 미끄러지는 것을 막습니다 (권장 100~200ms)."
        )

        // ---- 2. 자동 터치 ----
        sectionLabel("자동 터치 설정")
        val tc = card()
        sliderRow(
            tc, "터치 간격", { "${fmt1(it / 10.0 + 1.0)}초" }, 90, ((prefs.tapIntervalMs - 1000) / 100).toInt(),
        ) { prefs.tapIntervalMs = 1000L + it * 100L }
        divider(tc)
        styleSliders(tc, Prefs.KEY_TAP)
        divider(tc)
        styleSliders(tc, Prefs.KEY_TARGET, "조준점 크기")
        footnote("빨간 조준점(⊕)을 원하는 곳에 끌어다 놓고 손가락 버튼을 누르면 그 위치를 반복 터치합니다. 실제 터치는 조준점 정중앙 한 점이며, 크기·투명도는 보이는 모양만 바뀝니다.")

        // ---- 3. 자동 스크롤 ----
        sectionLabel("자동 스크롤 설정")
        val ac = card()
        sliderRow(
            ac, "스크롤 속도",
            { v ->
                val s = Prefs.MIN_AUTO_SPEED + v * 5
                val d = when {
                    s < 40 -> "아주 느림"; s < 100 -> "느림"; s < 250 -> "보통"; s < 500 -> "빠름"; else -> "아주 빠름"
                }
                "${s}dp/초 · $d"
            },
            (Prefs.MAX_AUTO_SPEED - Prefs.MIN_AUTO_SPEED) / 5, (prefs.autoSpeedDp - Prefs.MIN_AUTO_SPEED) / 5,
        ) { prefs.autoSpeedDp = Prefs.MIN_AUTO_SPEED + it * 5 }
        divider(ac)
        styleSliders(ac, Prefs.KEY_AUTO)
        footnote("화면을 직접 만지거나 다른 기능이 동작하면 1초 멈췄다가 이어집니다. 화면 끝까지 올린 뒤 다시 아래에서 시작할 때 아주 짧게 멈칫할 수 있어요.")

        // ---- 기타 ----
        sectionLabel("기타")
        val etc = card()
        navRow(etc, "접근성 설정 열기", null) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        divider(etc)
        navRow(etc, "앱 정보", "Android 13 이상에서 '제한된 설정 허용'이 필요할 때") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        divider(etc)
        switchRow(
            etc, R.drawable.ic_keyboard, 0xFF6B7280.toInt(), "키보드가 뜨면 숨기기",
            "입력할 때는 버튼을 숨기고, 키보드가 닫히면 다시 표시", prefs.hideOnKeyboard,
        ) { prefs.hideOnKeyboard = !prefs.hideOnKeyboard; apply(); refresh() }
        divider(etc)
        navRow(etc, "버튼 위치 초기화", null) {
            FloatingService.instance?.resetPositions()
                ?: listOf(Prefs.KEY_SCROLL, Prefs.KEY_TAP, Prefs.KEY_TARGET, Prefs.KEY_AUTO, Prefs.KEY_MINI)
                    .forEach(prefs::resetPosition)
            toast("버튼 위치를 초기화했습니다")
        }
        footnote(
            "버튼은 드래그해서 옮길 수 있고, 화면 좌우 끝으로 끌어다 놓으면 가장자리에 숨겨집니다. " +
                "게임·서비스에 따라 자동 터치가 이용약관 위반일 수 있습니다."
        )
    }

    // ================================================================ 구성 요소

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun ripple(content: android.graphics.drawable.Drawable?, mask: android.graphics.drawable.Drawable? = null) =
        RippleDrawable(ColorStateList.valueOf(c.ripple), content, mask ?: ColorDrawable(0xFFFFFFFF.toInt()))

    private fun sectionLabel(text: String) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.sub)
            setPadding(dp(6), dp(26), 0, dp(8))
        })
    }

    private fun footnote(text: String) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = 12.5f; setTextColor(c.sub)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(6), dp(8), dp(6), 0)
        })
    }

    private fun card(topMargin: Int = 0): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.card, 20)
            clipToOutline = true
        }
        root.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { this.topMargin = dp(topMargin) })
        return card
    }

    private fun divider(card: LinearLayout) {
        card.addView(View(this).apply { setBackgroundColor(c.divider) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = dp(18) })
    }

    private fun statusPill(on: Boolean): View {
        val color = if (on) c.good else c.bad
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(c.chip, 14)
            setPadding(dp(10), dp(5), dp(12), dp(5))
            addView(View(context).apply { background = rounded(color, 4) }, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(TextView(context).apply {
                text = when {
                    !on -> "서비스 꺼짐"
                    prefs.appModeEnabled -> "실행 중 · 앱 지정 모드"
                    else -> "실행 중"
                }
                textSize = 13f; setTextColor(c.text); setPadding(dp(8), 0, 0, 0)
            })
        }
    }

    private fun iconTile(iconRes: Int, bg: Int, sizeDp: Int = 40): View = FrameLayout(this).apply {
        background = rounded(bg, (sizeDp * 0.28f).roundToInt())
        val pad = dp((sizeDp * 0.22f).roundToInt())
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            setPadding(pad, pad, pad, pad)
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun styledSwitch(checked: Boolean) = Switch(this).apply {
        isChecked = checked
        isClickable = false; isFocusable = false   // 줄 전체를 눌러서 전환
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(c.accent, c.off))
        trackTintMode = PorterDuff.Mode.SRC_IN
    }

    /** 아이콘 + 제목/설명 + 스위치. 줄 전체를 누르면 전환 */
    private fun switchRow(
        card: LinearLayout, iconRes: Int, color: Int, title: String, desc: String, on: Boolean, onClick: () -> Unit,
    ) {
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            background = ripple(null)
            setOnClickListener { onClick() }
            addView(iconTile(iconRes, if (on) color else c.off))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                addView(TextView(context).apply {
                    text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.text)
                })
                addView(TextView(context).apply { text = desc; textSize = 13f; setTextColor(c.sub) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(styledSwitch(on))
        })
    }

    private fun featureRow(card: LinearLayout, f: Feature, iconRes: Int, title: String, desc: String, color: Int) {
        val on = Controls.isOn(prefs, f)
        switchRow(card, iconRes, color, title, if (on) "켜짐" else desc, on) {
            Controls.toggle(this, f)
            if (FloatingService.instance == null) toast("접근성 서비스를 켜야 화면에 버튼이 나타납니다")
            refresh()
        }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(c.text); gravity = Gravity.CENTER
        background = ripple(rounded(c.chip, 10), rounded(0xFFFFFFFF.toInt(), 10))
        setPadding(dp(10), 0, dp(10), 0)
        minWidth = dp(44)
        setOnClickListener { onClick() }
    }

    /** [라벨]   [−][−]  값  [+][+]  (기능 아래에 붙는 빠른 조절) */
    private fun stepperRow(
        card: LinearLayout, label: String, value: String,
        minus: List<Pair<String, Long>>, plus: List<Pair<String, Long>>, onStep: (Long) -> Unit,
    ) {
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), 0, dp(14), dp(14))
            addView(TextView(context).apply { text = label; textSize = 13f; setTextColor(c.sub) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)).apply { marginStart = dp(4) } }
            minus.forEach { (t, s) -> addView(smallButton(t) { onStep(s); refresh() }, lp()) }
            addView(TextView(context).apply {
                text = value; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.text)
                gravity = Gravity.CENTER; minWidth = dp(64)
            })
            plus.forEach { (t, s) -> addView(smallButton(t) { onStep(s); refresh() }, lp()) }
        })
    }

    /** 제목 ........ 값(강조색)  +  슬라이더 */
    private fun sliderRow(
        card: LinearLayout, title: String, value: (Int) -> String, max: Int, initial: Int,
        onValueClick: (() -> Unit)? = null, onChange: (Int) -> Unit,
    ): SeekBar {
        val valueView = TextView(this).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.accent)
            text = value(initial.coerceIn(0, max))
            if (onValueClick != null) {
                background = ripple(rounded(c.chip, 8), rounded(0xFFFFFFFF.toInt(), 8))
                setPadding(dp(8), dp(3), dp(8), dp(3))
                setOnClickListener { onValueClick() }
            }
        }
        val seek = SeekBar(this).apply {
            this.max = max
            progress = initial.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(c.accent)
            thumbTintList = ColorStateList.valueOf(c.accent)
            progressBackgroundTintList = ColorStateList.valueOf(c.off)
            setOnSeekBarChangeListener(object : SimpleSeek() {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    valueView.text = value(p)
                    if (fromUser) onChange(p)
                }
            })
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(c.text) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(valueView)
            })
            addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        })
        return seek
    }

    /** 크기 / 불투명도 슬라이더 두 줄 */
    private fun styleSliders(card: LinearLayout, key: String, sizeTitle: String = "버튼 크기") {
        val min = Prefs.minSize(key)
        sliderRow(card, sizeTitle, { "${it + min}dp" }, Prefs.MAX_SIZE_DP - min, prefs.sizeDp(key) - min) {
            prefs.setSizeDp(key, it + min); apply()
        }
        sliderRow(card, "불투명도", { "${it + 20}%" }, 80, (prefs.alpha(key) * 100).roundToInt() - 20) {
            prefs.setAlpha(key, (it + 20) / 100f); apply()
        }
    }

    /** 0.001~10초: 범위가 1만 배라 로그 스케일. 값을 누르면 직접 입력 */
    private fun logDurationSlider(card: LinearLayout) {
        fun toProgress(ms: Long) = (log10(ms.toDouble()) * 250).roundToInt()
        fun label(ms: Long) = "${fmtSec(ms)}초 ✎"
        lateinit var seek: SeekBar
        seek = sliderRow(
            card, "동작 시간", { p -> label(10.0.pow(p / 250.0).roundToLong()) }, 1000,
            toProgress(prefs.scrollDurationMs),
            onValueClick = {
                val input = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    hint = "0.001 ~ 10"; setText(fmtSec(prefs.scrollDurationMs)); selectAll()
                }
                AlertDialog.Builder(this)
                    .setTitle("동작 시간 (초)")
                    .setView(FrameLayout(this).apply {
                        setPadding(dp(20), dp(8), dp(20), 0); addView(input)
                    })
                    .setPositiveButton("적용") { _, _ ->
                        val sec = input.text.toString().toDoubleOrNull()
                        if (sec == null) toast("숫자를 입력하세요") else {
                            prefs.scrollDurationMs = (sec * 1000).roundToLong()
                            seek.progress = toProgress(prefs.scrollDurationMs)
                            refresh()
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            },
        ) { p -> prefs.scrollDurationMs = 10.0.pow(p / 250.0).roundToLong() }
    }

    private fun segmentedRow(card: LinearLayout, title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(c.text) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(c.chip, 12)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                options.forEachIndexed { i, o ->
                    val sel = i == selected
                    addView(TextView(context).apply {
                        text = o; textSize = 14f; gravity = Gravity.CENTER
                        typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (sel) c.text else c.sub)
                        background = if (sel) rounded(c.card, 10) else null
                        if (sel) elevation = dp(1).toFloat()
                        setOnClickListener { onSelect(i); refresh() }
                    }, LinearLayout.LayoutParams(0, dp(36), 1f))
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        })
    }

    /** 제목/설명 + › (누르면 이동/동작) */
    private fun navRow(card: LinearLayout, title: String, desc: String?, onClick: () -> Unit) {
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(16), dp(15))
            background = ripple(null)
            setOnClickListener { onClick() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(c.text) })
                if (desc != null) addView(TextView(context).apply {
                    text = desc; textSize = 13f; setTextColor(c.sub); maxLines = 2
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(c.sub); setPadding(dp(8), 0, 0, 0) })
        })
    }

    private fun textAction(card: LinearLayout, text: String, color: Int, onClick: () -> Unit) {
        card.addView(TextView(this).apply {
            this.text = text; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(15))
            background = ripple(null)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        background = ripple(rounded(c.accent, 14), rounded(0xFFFFFFFF.toInt(), 14))
        setOnClickListener { onClick() }
    }

    // ================================================================ 기타 동작

    private fun showDetectLog() {
        val logText = FloatingService.instance?.detectLog?.joinToString("\n")
            ?.ifEmpty { "아직 기록이 없습니다" } ?: "서비스가 꺼져 있습니다"
        AlertDialog.Builder(this)
            .setTitle("최근 감지 기록 (최신이 위)")
            .setMessage("현재 앱: ${FloatingService.instance?.foregroundPackage ?: "-"}\n\n$logText")
            .setPositiveButton("닫기", null)
            .show()
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
        AlertDialog.Builder(this)
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

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmtSec(ms: Long): String =
        String.format(Locale.US, "%.3f", ms / 1000.0).trimEnd('0').trimEnd('.')

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar) = Unit
        override fun onStopTrackingTouch(sb: SeekBar) = Unit
    }
}
