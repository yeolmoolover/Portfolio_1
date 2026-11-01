package com.portfolio.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import com.portfolio.screentranslator.overlay.BubbleController
import com.portfolio.screentranslator.overlay.CompareView
import com.portfolio.screentranslator.overlay.OverlayLayer
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

/**
 * Foreground service that owns the MediaProjection, watches the screen for changes,
 * runs OCR + translation, and paints the overlay.
 *
 * Auto mode loop: grab a frame every ~250 ms -> fingerprint -> if it differs from the
 * last frame the screen is moving (hide overlay) -> once it has been stable for ~600 ms
 * and differs from the last *translated* frame, translate it.
 */
class CaptureService : Service(), BubbleController.Callbacks {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
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
    private var bubble: BubbleController? = null
    private var compare: CompareView? = null

    private var watchJob: Job? = null
    private var translateJob: Job? = null
    private var lastFrameFp: IntArray? = null
    private var lastTranslatedFp: IntArray? = null
    /** Fingerprint of the same screen with our overlay drawn on it (capture includes overlays). */
    private var lastTranslatedFpWithOverlay: IntArray? = null
    private var stableSince = 0L
    private var overlayHiddenForMotion = false
    private var peeking = false
    private var currentBlocks: List<TranslatedBlock> = emptyList()
    private var lastSource: List<SourceBlock> = emptyList()
    private var lastBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        chain = EngineChain(prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                startForegroundCompat()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                startProjection(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stopIntent).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIF_ID, notif)
    }

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
        overlay = OverlayLayer(this, wm, alpha = prefs.opacity, fontScale = prefs.fontScale)
        bubble = BubbleController(this, wm, this).also { it.show(prefs.autoMode, prefs.comicMode) }
        compare = CompareView(this, wm)
        running = true
        startWatching()
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
                if (!prefs.autoMode || peeking) continue
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
        bubble?.setBusy(true)
        try {
            val bubbleRect = bubble?.bounds()
            val blocks = ocr.recognise(bmp, prefs.sourceLang)
                .filter { bubbleRect == null || !Rect.intersects(it.box, bubbleRect) }
            lastSource = blocks
            lastBitmap?.recycle(); lastBitmap = bmp
            val comic = prefs.comicMode
            if (blocks.isEmpty() && !comic) {
                lastTranslatedFp = fp; currentBlocks = emptyList()
                bubble?.setStatus("글자를 찾지 못했습니다")
                showOverlay(emptyList()); return
            }
            val req = buildRequest(bmp, blocks, comic)
            val key = chain.cacheKey(req, comic)
            val cachedHit = chain.cached(key) != null
            val result = chain.translate(req, key)
            val drawn = toDrawable(bmp, blocks, result)
            currentBlocks = drawn
            lastTranslatedFp = fp
            overlayHiddenForMotion = false
            val status = buildString {
                append(if (cachedHit) "저장된 번역" else result.engineUsed)
                append(" · ").append(drawn.size).append("개")
                chain.lastError?.let { append(" · 대체됨: ").append(it.take(220)) }
            }
            bubble?.setStatus(status)
            showOverlay(drawn)
        } catch (e: Exception) {
            Log.w(TAG, "translate failed", e)
            bubble?.setStatus("실패: ${e.message?.take(260)}")
            lastTranslatedFp = fp   // don't retry the same frame in a loop
        } finally {
            bubble?.setBusy(false)
        }
    }

    /**
     * Draws [blocks], waits a moment for the compositor, then records the fingerprint of the
     * screen *with* the overlay so the watcher does not mistake our own drawing for motion.
     */
    private suspend fun showOverlay(blocks: List<TranslatedBlock>) {
        withContext(Dispatchers.Main) { overlay?.blocks = blocks }
        delay(350)
        val after = grabFrame() ?: return
        val fp = ImageUtil.fingerprint(after)
        after.recycle()
        lastTranslatedFpWithOverlay = fp
        lastFrameFp = fp
        stableSince = System.currentTimeMillis()
    }

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
                out += TranslatedBlock(src.id, t.text, Rect(src.box), false, bg, fg)
            }
        }
        return out
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val i = Rect(); if (!i.setIntersect(a, b)) return 0f
        val inter = i.width().toFloat() * i.height()
        return inter / minOf(a.width().toFloat() * a.height(), b.width().toFloat() * b.height()).coerceAtLeast(1f)
    }

    // ---------------------------------------------------------------- bubble callbacks

    override fun onTranslateNow() {
        if (translateJob?.isActive == true) return
        translateJob = scope.launch {
            val bmp = grabFrame() ?: run { bubble?.setStatus("화면을 캡처하지 못했습니다"); return@launch }
            translateBitmap(bmp, ImageUtil.fingerprint(bmp))
        }
    }

    override fun onToggleAuto(): Boolean { prefs.autoMode = !prefs.autoMode; return prefs.autoMode }
    override fun onToggleComic(): Boolean { prefs.comicMode = !prefs.comicMode; return prefs.comicMode }

    override fun onCompare() {
        val bmp = lastBitmap; val blocks = lastSource
        if (bmp == null || blocks.isEmpty()) { toast("먼저 화면을 한 번 번역하세요"); return }
        val a = chain.engineByName(if (prefs.primaryEngine == "claude") "claude" else "gemini")
        val b = chain.engineByName(if (prefs.primaryEngine == "claude") "gemini" else "claude")
        if ((a.name == "claude" || b.name == "claude") && prefs.claudeKey.isBlank()) { toast("비교하려면 Claude 키도 필요합니다"); return }
        if (prefs.geminiKey.isBlank()) { toast("비교하려면 Gemini 키도 필요합니다"); return }
        bubble?.setBusy(true)
        scope.launch {
            val req = buildRequest(bmp, blocks, prefs.comicMode)
            val errs = StringBuilder()
            val ra = async { runCatching { chain.translateWith(a, req) }.onFailure { errs.append("${a.name}: ${it.message}\n") }.getOrNull() }
            val rb = async { runCatching { chain.translateWith(b, req) }.onFailure { errs.append("${b.name}: ${it.message}\n") }.getOrNull() }
            val resA = ra.await(); val resB = rb.await()
            withContext(Dispatchers.Main) {
                compare?.show(blocks, a.name to resA, b.name to resB, errs.toString().ifBlank { null })
            }
            bubble?.setBusy(false)
        }
    }

    override fun onClear() {
        currentBlocks = emptyList(); lastTranslatedFp = null; lastTranslatedFpWithOverlay = null
        main.post { overlay?.blocks = emptyList() }
        bubble?.setStatus(null)
    }

    override fun onStop() { stopSelf() }

    override fun onPeekOriginal(peeking: Boolean) {
        this.peeking = peeking
        if (peeking) main.post { overlay?.blocks = emptyList() }
        else scope.launch { showOverlay(currentBlocks) }
    }

    // ---------------------------------------------------------------- teardown

    private fun toast(msg: String) { main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }

    override fun onDestroy() {
        running = false
        watchJob?.cancel(); translateJob?.cancel(); scope.cancel()
        compare?.hide(); bubble?.hide()
        overlay?.destroy()
        virtualDisplay?.release(); reader?.close(); projection?.stop()
        lastBitmap?.recycle()
        super.onDestroy()
    }
}
