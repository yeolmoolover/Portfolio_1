package com.portfolio.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.portfolio.screentranslator.ocr.OcrEngine
import com.portfolio.screentranslator.overlay.CompareView
import com.portfolio.screentranslator.overlay.OverlayLayer
import com.portfolio.screentranslator.overlay.TopBar
import com.portfolio.screentranslator.translate.EngineChain
import com.portfolio.screentranslator.translate.SourceBlock
import com.portfolio.screentranslator.translate.TranslatedBlock
import com.portfolio.screentranslator.translate.TranslationRequest
import com.portfolio.screentranslator.translate.TranslationResult
import com.portfolio.screentranslator.util.ImageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the MediaProjection, watches the screen for changes,
 * runs OCR + translation, and paints the overlay.
 *
 * Controls: a small bar pinned under the status bar ([TopBar]: 번역 / 원문 보기 / ✕),
 * notification actions and the quick-settings tile. Status is shown in the notification
 * and on the app's main screen.
 *
 * Auto mode loop: grab a frame every ~250 ms -> fingerprint -> if it differs from the
 * last frame the screen is moving (hide overlay) -> once it has been stable for ~600 ms
 * and differs from the last *translated* frame, translate it.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_TRANSLATE = "translate"
        const val ACTION_TOGGLE_ORIGINAL = "toggle_original"
        const val ACTION_TOGGLE_AUTO = "toggle_auto"
        const val ACTION_COMPARE = "compare"
        const val ACTION_CLEAR = "clear"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val TAG = "CaptureService"
        @Volatile var running = false
    }

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var screenW = 0; private var screenH = 0; private var dpi = 0

    private val ocr = OcrEngine()
    private lateinit var chain: EngineChain
    private var overlay: OverlayLayer? = null
    private var compare: CompareView? = null

    private var watchJob: Job? = null
    private var translateJob: Job? = null
    private var lastFrameFp: IntArray? = null
    private var lastTranslatedFp: IntArray? = null
    /** Fingerprint of the same screen with our overlay drawn on it (capture includes overlays). */
    private var lastTranslatedFpWithOverlay: IntArray? = null
    private var stableSince = 0L
    private var overlayHiddenForMotion = false
    /** User asked to see the original (volume-up long press / notification). */
    private var showingOriginal = false
    private var currentBlocks: List<TranslatedBlock> = emptyList()
    private var lastSource: List<SourceBlock> = emptyList()
    private var lastBitmap: Bitmap? = null
    private var busy = false
    private var topBar: TopBar? = null
    private var overlayShownAt = 0L
    private var lastTriggerAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        chain = EngineChain(prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                // If something is wedged, make sure the bar and capture still go away.
                main.postDelayed({ if (running) { runCatching { onDestroy() }; android.os.Process.killProcess(android.os.Process.myPid()) } }, 1500)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_text)))
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                startProjection(code, data)
            }
            ACTION_TRANSLATE -> if (running) translateNow()
            ACTION_TOGGLE_ORIGINAL -> if (running) toggleOriginal()
            ACTION_TOGGLE_AUTO -> if (running) { prefs.autoMode = !prefs.autoMode; setStatus(if (prefs.autoMode) "자동 모드 켜짐" else "자동 모드 꺼짐") }
            ACTION_COMPARE -> if (running) compareEngines()
            ACTION_CLEAR -> if (running) clear()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- notification / status

    private fun pending(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, CaptureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "번역", pending(ACTION_TRANSLATE, 2)).build())
            .addAction(Notification.Action.Builder(null, if (showingOriginal) "번역 보기" else "원문 보기", pending(ACTION_TOGGLE_ORIGINAL, 3)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), pending(ACTION_STOP, 1)).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun setStatus(text: String) {
        prefs.lastStatus = text
        main.post {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) }
        }
    }

    // ---------------------------------------------------------------- projection

    private fun startProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try { mpm.getMediaProjection(code, data) } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e); null
        }
        if (mp == null) { toast(getString(R.string.perm_capture_denied)); stopSelf(); return }
        projection = mp
        // Android 14+ requires a callback to be registered before creating the virtual display.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        val metrics = resources.displayMetrics
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width(); screenH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val real = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenW = real.widthPixels; screenH = real.heightPixels
        }
        dpi = metrics.densityDpi
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "translate", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main,
        )

        // Per-block windows: boxes can be fully opaque while touches outside them pass through.
        overlay = OverlayLayer(this, wm, alpha = 1f, fontScale = prefs.fontScale, outlineStyle = prefs.textStyle == "outline", debug = prefs.debugBoxes)
        compare = CompareView(this, wm)
        topBar = TopBar(
            this, wm, statusBarHeight(),
            onTranslate = { translateNow() },
            onToggleOriginal = { toggleOriginal() },
            onStop = { stopSelf() },
            onOutsideTouch = { onUserTouchedScreen() },
        )
        running = true
        setStatus(if (prefs.autoMode) "자동 모드: 화면이 멈추면 번역합니다" else "상단 바의 '번역'을 누르면 번역합니다")
        startWatching()
    }

    // ---------------------------------------------------------------- touch / volume hooks

    private fun onUserTouchedScreen() {
        if (prefs.autoMode || showingOriginal) return
        if (currentBlocks.isEmpty()) return
        if (System.currentTimeMillis() - overlayShownAt < 400) return
        dismiss()
    }

    /** Hide the translation and forget the screen so nothing re-translates until asked. */
    private fun dismiss() {
        currentBlocks = emptyList()
        lastTranslatedFp = null; lastTranslatedFpWithOverlay = null
        overlayHiddenForMotion = false
        main.post { overlay?.blocks = emptyList() }
    }

    // ---------------------------------------------------------------- capture loop

    private fun grabFrame(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        return try { ImageUtil.toBitmap(img) } finally { img.close() }
    }

    private fun startWatching() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(250)
                if (showingOriginal) continue
                if (!prefs.autoMode) {
                    // Manual mode: only watch for scrolling, which dismisses the translation.
                    if (currentBlocks.isEmpty()) continue
                    val bmp = grabFrame() ?: continue
                    val fp = ImageUtil.fingerprint(bmp)
                    bmp.recycle()
                    if (lastTranslatedFpWithOverlay != null && ImageUtil.diff(lastTranslatedFpWithOverlay, fp) > 6 &&
                        System.currentTimeMillis() - overlayShownAt > 600) dismiss()
                    continue
                }
                val bmp = grabFrame() ?: continue
                val fp = ImageUtil.fingerprint(bmp)
                val moving = ImageUtil.diff(lastFrameFp, fp) > 6
                lastFrameFp = fp
                val now = System.currentTimeMillis()
                if (moving) {
                    stableSince = now
                    if (!overlayHiddenForMotion && currentBlocks.isNotEmpty()) {
                        overlayHiddenForMotion = true
                        withContext(Dispatchers.Main) { overlay?.blocks = emptyList() }
                    }
                    bmp.recycle()
                    continue
                }
                if (now - stableSince < 600) { bmp.recycle(); continue }
                if (ImageUtil.diff(lastTranslatedFpWithOverlay, fp) <= 6) { bmp.recycle(); continue }
                if (ImageUtil.diff(lastTranslatedFp, fp) <= 6) {
                    // same screen as last translation: just restore the overlay
                    if (overlayHiddenForMotion) {
                        overlayHiddenForMotion = false
                        showOverlay(currentBlocks)
                    }
                    bmp.recycle(); continue
                }
                if (translateJob?.isActive == true) { bmp.recycle(); continue }
                translateJob = launch { translateBitmap(bmp, fp) }
            }
        }
    }

    // ---------------------------------------------------------------- translate

    private suspend fun translateBitmap(bmp: Bitmap, fp: IntArray) {
        busy = true
        topBar?.setBusy(true)
        try {
            val t0 = System.currentTimeMillis()
            val ocrInput = ImageUtil.boostForOcr(bmp)
            val ocrResult = try { ocr.recognise(ocrInput, prefs.sourceLang) } finally { ocrInput.recycle() }
            val tOcr = System.currentTimeMillis() - t0
            val topInset = statusBarHeight()
            val barRect = topBar?.bounds()
            val blocks = mergeOverlapping(
                ocrResult.blocks.filter { it.box.bottom > topInset + 4 }
                    .filter { barRect == null || !Rect.intersects(it.box, barRect) }
                    .map { b -> if (b.vertical) refineVerticalSafe(bmp, b) else b }
            )
            lastSource = blocks
            lastBitmap?.recycle(); lastBitmap = bmp
            val comic = prefs.comicMode
            if (blocks.isEmpty() && (!comic || ocrResult.koreanBlocks > 0)) {
                // Nothing foreign on screen (or the screen is already Korean): draw nothing.
                lastTranslatedFp = fp; currentBlocks = emptyList()
                setStatus(if (ocrResult.koreanBlocks > 0) "이미 한국어 화면입니다" else "번역할 글자를 찾지 못했습니다")
                showOverlay(emptyList()); return
            }
            val req = buildRequest(bmp, blocks, comic)
            val key = chain.cacheKey(req, comic)
            val cachedHit = chain.cached(key) != null
            val t1 = System.currentTimeMillis()
            val result = chain.translate(req, key)
            val tApi = System.currentTimeMillis() - t1
            val drawn = toDrawable(bmp, blocks, result)
            currentBlocks = drawn
            lastTranslatedFp = fp
            overlayHiddenForMotion = false
            showingOriginal = false
            topBar?.setShowingOriginal(false)
            val status = buildString {
                append(if (cachedHit) "저장된 번역" else result.engineUsed)
                append(" · ").append(drawn.count { !it.isSfx }).append("/").append(blocks.size).append("개 블록")
                val missing = blocks.size - drawn.count { !it.isSfx }
                if (missing > 0) append(" (").append(missing).append("개 누락)")
                append(" · 인식 ").append(tOcr).append("ms · 번역 ").append(tApi).append("ms")
                if (comic) append(" (만화 모드: 스크린샷 포함)")
                chain.lastError?.let { append("\n대체됨: ").append(it) }
            }
            setStatus(status)
            showOverlay(drawn)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "translate failed", e)
            setStatus("실패: ${e.javaClass.simpleName}: ${e.message}")
            lastTranslatedFp = fp   // don't retry the same frame in a loop
        } finally {
            busy = false
            topBar?.setBusy(false)
        }
    }

    /**
     * Draws [blocks], waits a moment for the compositor, then records the fingerprint of the
     * screen *with* the overlay so the watcher does not mistake our own drawing for motion.
     */
    private suspend fun showOverlay(blocks: List<TranslatedBlock>) {
        withContext(Dispatchers.Main) { overlay?.blocks = blocks }
        delay(150)
        val after = grabFrame() ?: return
        val fp = ImageUtil.fingerprint(after)
        after.recycle()
        lastTranslatedFpWithOverlay = fp
        lastFrameFp = fp
        stableSince = System.currentTimeMillis()
        overlayShownAt = stableSince
    }

    /**
     * After ink measurement, several ML Kit "columns" of one bubble resolve to the same area.
     * Merge blocks whose boxes overlap heavily into one, text joined right-to-left.
     */
    private fun mergeOverlapping(blocks: List<SourceBlock>): List<SourceBlock> {
        val groups = ArrayList<MutableList<SourceBlock>>()
        for (b in blocks) {
            val g = groups.firstOrNull { grp -> grp.any { o -> overlapRatio(o.box, b.box) > 0.5f } }
            if (g != null) g += b else groups += mutableListOf(b)
        }
        var id = 1
        return groups.map { g ->
            if (g.size == 1) g[0].copy(id = id++) else {
                val sorted = g.sortedByDescending { it.hintX }
                val box = Rect(sorted[0].box); for (o in sorted) box.union(o.box)
                val best = sorted.maxByOrNull { it.lines.size } ?: sorted[0]
                SourceBlock(id++, sorted.joinToString(" ") { it.text.replace("\n", "") }, box, true, best.lines, best.lineHeight, hintX = best.hintX)
            }
        }
    }

    /** [refineVertical] with a hard time limit; falls back to the raw block on any problem. */
    private suspend fun refineVerticalSafe(bmp: Bitmap, b: SourceBlock): SourceBlock =
        withTimeoutOrNull(1500) {
            withContext(Dispatchers.Default) { runCatching { refineVertical(bmp, b) }.getOrDefault(b) }
        } ?: b

    /** Replace ML Kit's near-zero-width vertical boxes with the measured ink extent. */
    private fun refineVertical(bmp: Bitmap, b: SourceBlock): SourceBlock {
        val (bg, _) = ImageUtil.sampleColors(bmp, b.box)
        val ink = ImageUtil.refineVerticalText(bmp, b.box, ImageUtil.luminance(bg)) ?: return b
        return b.copy(box = ink.box, lines = ink.columns, lineHeight = ink.glyph, hintX = b.box.centerX())
    }

    private fun statusBarHeight(): Int = try {
        if (Build.VERSION.SDK_INT >= 30)
            wm.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        else 0
    } catch (e: Exception) { 0 }

    private fun buildRequest(bmp: Bitmap, blocks: List<SourceBlock>, comic: Boolean): TranslationRequest {
        var jpeg: ByteArray? = null; var iw = 0; var ih = 0
        if (comic) {
            val (bytes, w, h) = ImageUtil.toJpeg(bmp, maxLongEdge = 1280)
            jpeg = bytes; iw = w; ih = h
        }
        return TranslationRequest(
            blocks = blocks, imageJpeg = jpeg, imageWidth = iw, imageHeight = ih,
            screenWidth = bmp.width, screenHeight = bmp.height,
            sourceLangHint = prefs.sourceLang, history = chain.historySnapshot(),
        )
    }

    private fun toDrawable(bmp: Bitmap, blocks: List<SourceBlock>, result: TranslationResult): List<TranslatedBlock> {
        val byId = blocks.associateBy { it.id }
        val out = ArrayList<TranslatedBlock>()
        val gap = (6 * resources.displayMetrics.density).toInt()
        /**
         * Horizontal panel for a vertical block: as wide as the column height (what a bubble
         * usually allows), at least ~4.5 glyphs wide, placed inside the free span between the
         * neighbouring blocks at the same height - shifted sideways rather than squeezed.
         */
        fun horizontalBox(src: SourceBlock): Rect {
            val cx = src.box.centerX(); val cy = src.box.centerY()
            val halfH = (src.box.height() * 0.65f).toInt()          // a bit taller than the columns
            val top = (cy - halfH).coerceAtLeast(0); val bottom = (cy + halfH).coerceAtMost(bmp.height)
            val minW = maxOf(src.box.width(), (src.lineHeight * 4.5f).toInt())
            val want = maxOf(minW, (src.box.height() * 1.1f).toInt()).coerceAtMost((bmp.width * 0.8f).toInt())
            // free horizontal span at this height
            var spanL = 0; var spanR = bmp.width
            val others = blocks.filter { it.id != src.id }.map { it.box } + out.map { it.box }
            for (o in others) {
                if (o.bottom <= top || o.top >= bottom) continue
                if (o.right <= cx && o.right + gap > spanL) spanL = o.right + gap
                if (o.left >= cx && o.left - gap < spanR) spanR = o.left - gap
            }
            val w = want.coerceAtMost(spanR - spanL).coerceAtLeast(minOf(minW, spanR - spanL))
            var left = cx - w / 2
            if (left < spanL) left = spanL
            if (left + w > spanR) left = spanR - w
            left = left.coerceAtLeast(0)
            return Rect(left, top, left + w, bottom)
        }
        for (t in result.items) {
            if (t.isSfx) {
                val f = t.boxNorm ?: continue
                val r = Rect(
                    (f[0] * bmp.width).toInt(), (f[1] * bmp.height).toInt(),
                    ((f[0] + f[2]) * bmp.width).toInt(), ((f[1] + f[3]) * bmp.height).toInt(),
                )
                // avoid drawing SFX on top of a speech bubble we already translated
                if (blocks.any { Rect.intersects(it.box, r) && overlapRatio(it.box, r) > 0.5f }) continue
                out += TranslatedBlock(null, t.text, r, true, 0, 0xFFFFFFFF.toInt())
            } else {
                val src = byId[t.sourceId] ?: continue
                val (bg, fg) = ImageUtil.sampleColors(bmp, src.box)
                if (src.vertical) {
                    // Write the translation horizontally, centred on the original columns.
                    out += TranslatedBlock(src.id, t.text, horizontalBox(src), false, bg, fg, src.lines.map { Rect(it) }, src.lineHeight, vertical = true, srcBox = Rect(src.box))
                } else {
                    out += TranslatedBlock(src.id, t.text, Rect(src.box), false, bg, fg, src.lines.map { Rect(it) }, src.lineHeight)
                }
            }
        }
        return out
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val i = Rect(); if (!i.setIntersect(a, b)) return 0f
        val inter = i.width().toFloat() * i.height()
        return inter / minOf(a.width().toFloat() * a.height(), b.width().toFloat() * b.height()).coerceAtLeast(1f)
    }

    // ---------------------------------------------------------------- user actions

    private var translateStartedAt = 0L

    private fun translateNow() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 500) return
        lastTriggerAt = now
        if (translateJob?.isActive == true) {
            if (now - translateStartedAt < 20_000) { setStatus("번역 중…"); return }
            translateJob?.cancel()                  // stuck: drop it and start fresh
            setStatus("이전 번역이 20초를 넘겨 취소했습니다")
        }
        translateStartedAt = now
        showingOriginal = false
        topBar?.setShowingOriginal(false)
        translateJob = scope.launch {
            val bmp = grabFrame() ?: run { setStatus("화면을 캡처하지 못했습니다"); return@launch }
            val done = withTimeoutOrNull(25_000) { translateBitmap(bmp, ImageUtil.fingerprint(bmp)) }
            if (done == null) { busy = false; topBar?.setBusy(false); setStatus("번역이 25초를 넘겨 중단했습니다 (네트워크/엔진 확인)") }
        }
    }

    private fun toggleOriginal() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 500) return
        lastTriggerAt = now
        if (currentBlocks.isEmpty()) { translateNow(); return }   // nothing to toggle: translate instead
        showingOriginal = !showingOriginal
        topBar?.setShowingOriginal(showingOriginal)
        if (showingOriginal) {
            main.post { overlay?.blocks = emptyList() }
            setStatus("원문 표시 중 ('번역 보기'로 복귀)")
        } else {
            scope.launch { showOverlay(currentBlocks) }
            setStatus(prefs.lastStatus.substringBefore("\n원문"))
        }
    }

    private fun compareEngines() {
        val bmp = lastBitmap; val blocks = lastSource
        if (bmp == null || blocks.isEmpty()) { toast("먼저 화면을 한 번 번역하세요"); return }
        val a = chain.engineByName(if (prefs.primaryEngine == "claude") "claude" else "gemini")
        val b = chain.engineByName(if (prefs.primaryEngine == "claude") "gemini" else "claude")
        if (prefs.claudeKey.isBlank() || prefs.geminiKey.isBlank()) { toast("비교하려면 Gemini와 Claude 키가 모두 필요합니다"); return }
        setStatus("엔진 비교 중…")
        scope.launch {
            val req = buildRequest(bmp, blocks, prefs.comicMode)
            val errs = StringBuilder()
            val ra = async { runCatching { chain.translateWith(a, req) }.onFailure { errs.append("${a.name}: ${it.message}\n") }.getOrNull() }
            val rb = async { runCatching { chain.translateWith(b, req) }.onFailure { errs.append("${b.name}: ${it.message}\n") }.getOrNull() }
            val resA = ra.await(); val resB = rb.await()
            withContext(Dispatchers.Main) {
                compare?.show(blocks, a.name to resA, b.name to resB, errs.toString().ifBlank { null })
            }
            setStatus("엔진 비교 표시 중 (패널을 탭하면 닫힘)")
        }
    }

    private fun clear() {
        currentBlocks = emptyList(); lastTranslatedFp = null; lastTranslatedFpWithOverlay = null
        main.post { overlay?.blocks = emptyList() }
        setStatus("번역을 지웠습니다")
    }

    // ---------------------------------------------------------------- teardown

    private fun toast(msg: String) { main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }

    override fun onDestroy() {
        running = false
        watchJob?.cancel(); translateJob?.cancel(); scope.cancel()
        compare?.hide()
        overlay?.destroy()
        topBar?.destroy()
        virtualDisplay?.release(); reader?.close(); projection?.stop()
        lastBitmap?.recycle()
        prefs.lastStatus = "중지됨"
        super.onDestroy()
    }
}
