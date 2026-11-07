package com.portfolio.screentranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.perm_capture_denied, Toast.LENGTH_SHORT).show(); return@registerForActivityResult
        }
        val i = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, res.resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, i)
        finish()   // get out of the way so the user is back on the app they want translated
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        // targetSdk 35 draws edge-to-edge: keep content below the status bar / above the nav bar.
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val geminiKey = findViewById<TextInputEditText>(R.id.gemini_key).apply { setText(prefs.geminiKey) }
        val claudeKey = findViewById<TextInputEditText>(R.id.claude_key).apply { setText(prefs.claudeKey) }
        val geminiModel = findViewById<TextInputEditText>(R.id.gemini_model).apply { setText(prefs.geminiModel) }
        val gemmaModel = findViewById<TextInputEditText>(R.id.gemma_model).apply { setText(prefs.gemmaModel) }
        val claudeModel = findViewById<TextInputEditText>(R.id.claude_model).apply { setText(prefs.claudeModel) }

        val engineValues = resources.getStringArray(R.array.engine_values)
        val engineLabels = resources.getStringArray(R.array.engines)
        val engine = findViewById<MaterialAutoCompleteTextView>(R.id.engine).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, engineLabels))
            setText(engineLabels[engineValues.indexOf(prefs.primaryEngine).coerceAtLeast(0)], false)
        }
        val langValues = resources.getStringArray(R.array.source_lang_values)
        val langLabels = resources.getStringArray(R.array.source_langs)
        val lang = findViewById<MaterialAutoCompleteTextView>(R.id.source_lang).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, langLabels))
            setText(langLabels[langValues.indexOf(prefs.sourceLang).coerceAtLeast(0)], false)
        }
        val comic = findViewById<SwitchMaterial>(R.id.comic_mode).apply { isChecked = prefs.comicMode }
        val volume = findViewById<SwitchMaterial>(R.id.volume_trigger).apply { isChecked = prefs.volumeTrigger }
        val auto = findViewById<SwitchMaterial>(R.id.auto_mode).apply { isChecked = prefs.autoMode }
        val opacity = findViewById<Slider>(R.id.opacity).apply { value = prefs.opacity }
        val fontScale = findViewById<Slider>(R.id.font_scale).apply { value = prefs.fontScale }

        fun save() {
            prefs.geminiKey = geminiKey.text.toString()
            prefs.claudeKey = claudeKey.text.toString()
            prefs.geminiModel = geminiModel.text.toString().ifBlank { "gemini-3.5-flash-lite" }
            prefs.gemmaModel = gemmaModel.text.toString().ifBlank { "gemma-4-31b-it" }
            prefs.claudeModel = claudeModel.text.toString().ifBlank { "claude-haiku-5-5" }
            prefs.primaryEngine = engineValues[engineLabels.indexOf(engine.text.toString()).coerceAtLeast(0)]
            prefs.sourceLang = langValues[langLabels.indexOf(lang.text.toString()).coerceAtLeast(0)]
            prefs.comicMode = comic.isChecked
            prefs.volumeTrigger = volume.isChecked
            prefs.autoMode = auto.isChecked
            prefs.opacity = opacity.value
            prefs.fontScale = fontScale.value
        }

        findViewById<MaterialButton>(R.id.btn_start).setOnClickListener {
            save()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.perm_overlay, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            if (prefs.geminiKey.isBlank() && prefs.claudeKey.isBlank()) {
                Toast.makeText(this, "Gemini 또는 Claude API 키를 먼저 입력하세요", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            // Android 14+: ask for the whole screen only (no "single app" picker in the dialog).
            val captureIntent = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            projectionLauncher.launch(captureIntent)
        }
        findViewById<MaterialButton>(R.id.btn_stop).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        findViewById<MaterialButton>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_compare).setOnClickListener {
            if (!CaptureService.running) { Toast.makeText(this, "번역이 실행 중일 때만 가능합니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_COMPARE))
            finish()
        }
        findViewById<MaterialButton>(R.id.btn_original).setOnClickListener {
            if (!CaptureService.running) { Toast.makeText(this, "번역이 실행 중일 때만 가능합니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_TOGGLE_ORIGINAL))
            finish()
        }
        findViewById<MaterialButton>(R.id.btn_translate_now).setOnClickListener {
            if (!CaptureService.running) { Toast.makeText(this, "먼저 번역 시작을 누르세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_TRANSLATE))
            finish()
        }
        updateUsage()
    }

    override fun onResume() { super.onResume(); updateUsage() }

    private fun updateUsage() {
        findViewById<android.widget.TextView>(R.id.usage).text = getString(
            R.string.usage_today, prefs.usage("gemini"), prefs.usage("gemma"), prefs.usage("claude"),
        )
        findViewById<android.widget.TextView>(R.id.last_status).text =
            getString(R.string.last_status, prefs.lastStatus.ifBlank { "-" })
    }
}
