package com.gyarulens.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs

/**
 * 화면 캡처 → 글자 인식 → 갸루 말투 변환 → 오버레이 표시를 담당하는 포그라운드 서비스.
 * 떠 있는 말풍선 버튼: 탭 = 한 번 번역 / 다시 탭 = 지우기 / 길게 = 실시간 모드 / 끌기 = 이동
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.gyarulens.app.action.START"
        const val ACTION_STOP = "com.gyarulens.app.action.STOP"
        const val ACTION_TOGGLE_AUTO = "com.gyarulens.app.action.TOGGLE_AUTO"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "gyarulens_running"
        private const val NOTIFICATION_ID = 7

        /** 실시간 모드에서 이 비율 이상 화면이 바뀌면 다시 번역 */
        private const val CHANGE_THRESHOLD = 0.012f

        private const val PINK = 0xFFFF4FA3.toInt()
        private const val PURPLE = 0xFF8B5CF6.toInt()
        private const val RED = 0xFFE53935.toInt()

        @Volatile
        var isRunning = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val ocr = OcrEngine()
    private lateinit var prefs: Prefs
    private lateinit var translator: Translator

    private lateinit var uiContext: Context
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureThread: HandlerThread? = null
    private var capturer: ScreenCapturer? = null

    private var overlay: OverlayView? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // 아래 상태는 전부 메인 스레드에서만 만진다
    private var busy = false
    private var autoMode = false
    private var refSig: Signature? = null
    private var stopping = false
    private var errorUntil = 0L

    /** 지우기·실시간 끄기·회전 때마다 증가. 그 전에 시작한 번역 결과는 버린다. */
    private var generation = 0

    @Volatile
    private var bubbleRect: Rect? = null

    private val autoTick: Runnable = object : Runnable {
        override fun run() {
            if (!autoMode) return
            main.postDelayed(this, intervalMs())
            if (!busy) checkForChange()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        translator = Translator(prefs)
        uiContext = makeUiContext()
        wm = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_TOGGLE_AUTO -> if (projection != null) setAuto(!autoMode) else stopEverything()
            ACTION_STOP -> stopEverything()
            else -> if (projection == null) stopEverything()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 회전 직후엔 크기 정보가 늦게 바뀔 수 있어서 살짝 기다린다
        main.postDelayed({
            handleSizeChange()
            keepBubbleOnScreen()
        }, 300)
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        main.removeCallbacksAndMessages(null)
        removeWindows()
        releaseCapture()
        try {
            worker.execute { ocr.close() }
        } catch (e: RejectedExecutionException) {
        }
        worker.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── 시작 / 종료 ─────────────────────────

    private fun handleStart(intent: Intent) {
        // 안드로이드 14부터는 반드시 포그라운드가 된 다음에 캡처 권한을 꺼내야 한다
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            toast("실행 준비에 실패했어요: ${e.message ?: e.javaClass.simpleName}")
            stopEverything()
            return
        }

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            stopEverything()
            return
        }

        releaseCapture() // 혹시 이전 세션이 남아 있으면 정리
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            toast("화면 캡처 권한을 받지 못했어요")
            stopEverything()
            return
        }
        projection = proj
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // 사용자가 상단 바 등에서 화면 공유를 끈 경우
                main.post { stopEverything() }
            }
        }
        projectionCallback = callback
        proj.registerCallback(callback, main) // 안드로이드 14+: 캡처 시작 전에 등록 필수

        val thread = HandlerThread("GyaruCapture")
        thread.start()
        captureThread = thread
        val size = realSize()
        val cap = ScreenCapturer(proj, Handler(thread.looper)) { msg ->
            main.post {
                toast(msg)
                stopEverything()
            }
        }
        capturer = cap
        cap.start(size.x, size.y, resources.displayMetrics.densityDpi)

        stopping = false
        isRunning = true
        showWindows(size)
        toast("말풍선 탭 = 번역 / 길게 누르기 = 실시간 모드 ✨")
    }

    private fun stopEverything() {
        if (stopping) return
        stopping = true
        isRunning = false
        autoMode = false
        main.removeCallbacks(autoTick)
        removeWindows()
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture() {
        capturer?.release()
        capturer = null
        captureThread?.quitSafely()
        captureThread = null
        val p = projection
        projection = null
        if (p != null) {
            projectionCallback?.let {
                try {
                    p.unregisterCallback(it)
                } catch (e: Exception) {
                }
            }
            try {
                p.stop()
            } catch (e: Exception) {
            }
        }
        projectionCallback = null
        refSig = null
        busy = false
    }

    // ───────────────────────── 떠 있는 창들 ─────────────────────────

    private fun showWindows(size: Point) {
        removeWindows()

        val ov = OverlayView(uiContext)
        val overlayParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        overlayParams.gravity = Gravity.TOP or Gravity.START
        // 안드로이드 12+: 창 불투명도가 0.8 이하여야 아래 앱으로 터치가 통과된다
        overlayParams.alpha = 0.78f
        if (Build.VERSION.SDK_INT >= 28) {
            overlayParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        overlayParams.title = "GyaruLens overlay"
        try {
            wm.addView(ov, overlayParams)
            overlay = ov
        } catch (e: Exception) {
            toast("'다른 앱 위에 표시' 권한이 필요해요")
            stopEverything()
            return
        }

        val bubbleSize = dp(54)
        val b = TextView(uiContext)
        b.text = "갸"
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.includeFontPadding = false
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(PINK)
        bg.setStroke(dp(2), Color.WHITE)
        b.background = bg

        val bp = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        bp.gravity = Gravity.TOP or Gravity.START
        bp.x = size.x - bubbleSize - dp(10)
        bp.y = size.y / 3
        bp.title = "GyaruLens bubble"
        b.setOnTouchListener(BubbleTouchListener())
        try {
            wm.addView(b, bp)
            bubble = b
            bubbleParams = bp
        } catch (e: Exception) {
            toast("말풍선을 띄우지 못했어요")
            stopEverything()
            return
        }
        updateBubble()
    }

    private fun removeWindows() {
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        overlay = null
        bubble = null
        bubbleParams = null
    }

    private fun updateBubble() {
        val b = bubble ?: return
        val color = when {
            SystemClock.uptimeMillis() < errorUntil -> RED
            autoMode -> PURPLE
            else -> PINK
        }
        (b.background as? GradientDrawable)?.setColor(color)
        b.text = if (busy) "…" else "갸"
    }

    private fun flashBubbleError() {
        errorUntil = SystemClock.uptimeMillis() + 900
        updateBubble()
        main.postDelayed({ updateBubble() }, 950)
    }

    private fun updateBubbleRect() {
        val b = bubble
        if (b == null) {
            bubbleRect = null
            return
        }
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        bubbleRect = Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
    }

    private fun keepBubbleOnScreen() {
        val b = bubble ?: return
        val p = bubbleParams ?: return
        val size = realSize()
        val w = if (b.width > 0) b.width else dp(54)
        val h = if (b.height > 0) b.height else dp(54)
        p.x = p.x.coerceIn(0, maxOf(0, size.x - w))
        p.y = p.y.coerceIn(0, maxOf(0, size.y - h))
        try {
            wm.updateViewLayout(b, p)
        } catch (e: Exception) {
        }
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var target: View? = null
        private val slop = ViewConfiguration.get(uiContext).scaledTouchSlop
        private val longPressRunnable = Runnable {
            longPressed = true
            target?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onBubbleLongPress()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target = v
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longPressed = false
                    main.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && !longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, p)
                        } catch (e: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        refSig = null // 말풍선 자리가 바뀌었으니 다음 비교는 새로
                    } else if (!longPressed) {
                        v.performClick()
                        onBubbleTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPressRunnable)
            }
            return true
        }
    }

    private fun onBubbleTap() {
        if (autoMode) {
            setAuto(false)
            return
        }
        if (busy) return
        val ov = overlay
        if (ov != null && ov.hasItems()) {
            generation++
            ov.clear()
            refSig = null
            return
        }
        translateNow()
    }

    private fun onBubbleLongPress() {
        setAuto(!autoMode)
    }

    private fun setAuto(on: Boolean) {
        if (projection == null) return
        autoMode = on
        main.removeCallbacks(autoTick)
        updateBubble()
        updateNotification()
        if (on) {
            toast("실시간 모드 ON ✨ (말풍선 탭하면 꺼져요)")
            refSig = null
            if (!busy) translateNow()
            main.postDelayed(autoTick, intervalMs())
        } else {
            toast("실시간 모드 OFF")
            generation++
            overlay?.clear()
            refSig = null
        }
    }

    private fun intervalMs(): Long = prefs.autoIntervalSec.coerceIn(1, 30) * 1000L

    // ───────────────────────── 번역 파이프라인 ─────────────────────────

    /** 실시간 모드: 번역창이 없는 부분의 화면이 바뀌었을 때만 다시 번역 */
    private fun checkForChange() {
        val cap = capturer ?: return
        val ref = refSig
        if (ref == null) {
            translateNow()
            return
        }
        if (handleSizeChange()) return
        busy = true
        val masks = currentMasks(cap.config.scale)
        val now = SystemClock.uptimeMillis()
        cap.grab(0L, now + 800) { bmp ->
            if (bmp == null) {
                main.post { busy = false }
                return@grab
            }
            runOnWorker(onRejected = { bmp.recycle() }) {
                val fraction = try {
                    ref.changedFraction(Signature.of(bmp), masks)
                } catch (e: Exception) {
                    1f
                } finally {
                    bmp.recycle()
                }
                main.post {
                    busy = false
                    if (autoMode && fraction > CHANGE_THRESHOLD) translateNow()
                }
            }
        }
    }

    private fun translateNow() {
        val cap = capturer ?: return
        if (busy) return
        if (handleSizeChange()) {
            main.postDelayed({ if (!busy) translateNow() }, 500)
            return
        }
        busy = true
        updateBubble()
        updateBubbleRect()

        val ov = overlay
        val hide = ov != null && ov.hasItems() && prefs.hideWhileCapture
        if (hide) ov?.visibility = View.INVISIBLE // 우리 번역창이 캡처에 찍히지 않게
        val t0 = SystemClock.uptimeMillis()
        val notBefore = if (hide) t0 + 90 else 0L
        val deadline = t0 + (if (hide) 450L else 1500L)
        val scale = cap.config.scale
        val exclude = bubbleRect
        val script = prefs.ocrScript
        val gen = generation

        cap.grab(notBefore, deadline) { bmp ->
            if (bmp == null) {
                main.post { finishWithError("화면을 못 읽었어 ㅠ 다시 눌러 줘") }
                return@grab
            }
            runOnWorker(onRejected = { bmp.recycle() }) { process(bmp, scale, exclude, script, gen) }
        }
    }

    /** 작업 스레드: 글자 인식 → 변환 */
    private fun process(bmp: Bitmap, scale: Float, exclude: Rect?, script: String, gen: Int) {
        try {
            val blocks = ocr.recognize(bmp, script, scale, exclude)
            val sig = Signature.of(bmp)
            if (blocks.isEmpty()) {
                main.post { showResult(emptyList(), sig, "글자를 못 찾았어~ 🥺", gen) }
                return
            }
            val translated = translator.translate(blocks.map { it.text })
            val items = ArrayList<OverlayView.Item>()
            for (block in blocks) {
                val t = translated[block.text] ?: continue
                if (sameText(t, block.text)) continue
                items.add(OverlayView.Item(block.rect, t, block.lineCount))
            }
            main.post { showResult(items, sig, "바꿀 글자가 없었어~", gen) }
        } catch (e: ApiException) {
            val msg = e.message ?: "AI 요청 실패"
            main.post { finishWithError(msg, isApiError = true) }
        } catch (e: Exception) {
            val msg = "오류: ${e.message ?: e.javaClass.simpleName}"
            main.post { finishWithError(msg) }
        } finally {
            bmp.recycle()
        }
    }

    private fun showResult(items: List<OverlayView.Item>, sig: Signature, emptyMessage: String, gen: Int) {
        busy = false
        val ov = overlay
        if (ov == null) return
        ov.visibility = View.VISIBLE
        updateBubble()
        if (gen != generation) return // 그사이 사용자가 지웠거나 실시간 모드를 껐음
        val size = realSize()
        ov.setItems(items, size.x, size.y, prefs.textScale)
        refSig = sig
        if (items.isEmpty() && !autoMode) toast(emptyMessage)
    }

    private fun finishWithError(message: String, isApiError: Boolean = false) {
        busy = false
        overlay?.visibility = View.VISIBLE
        if (isApiError && autoMode) {
            // 키 오류·한도 초과일 때 계속 요청하지 않도록 실시간 모드를 끈다
            autoMode = false
            main.removeCallbacks(autoTick)
            updateNotification()
            overlay?.clear()
            toast("$message\n(실시간 모드는 꺼둘게)")
        } else {
            toast(message)
        }
        flashBubbleError()
    }

    /** 화면 크기(회전)가 바뀌었으면 캡처 크기를 맞추고 true */
    private fun handleSizeChange(): Boolean {
        val cap = capturer ?: return false
        val size = realSize()
        val cfg = cap.config
        if (size.x == cfg.screenW && size.y == cfg.screenH) return false
        cap.resize(size.x, size.y, resources.displayMetrics.densityDpi)
        generation++
        overlay?.clear()
        refSig = null
        return true
    }

    /** 변화 비교에서 뺄 영역: 번역 상자 + 말풍선 (캡처 이미지 좌표) */
    private fun currentMasks(scale: Float): List<RectF> {
        val list = ArrayList<RectF>()
        val margin = 3f
        overlay?.boxes?.forEach { r ->
            list.add(RectF((r.left - margin) * scale, (r.top - margin) * scale, (r.right + margin) * scale, (r.bottom + margin) * scale))
        }
        updateBubbleRect()
        bubbleRect?.let { r ->
            list.add(RectF((r.left - margin) * scale, (r.top - margin) * scale, (r.right + margin) * scale, (r.bottom + margin) * scale))
        }
        return list
    }

    private fun sameText(a: String, b: String): Boolean {
        val na = a.filterNot { it.isWhitespace() }.lowercase()
        val nb = b.filterNot { it.isWhitespace() }.lowercase()
        return na == nb
    }

    private fun runOnWorker(onRejected: () -> Unit, task: () -> Unit) {
        try {
            worker.execute { task() }
        } catch (e: RejectedExecutionException) {
            onRejected()
        }
    }

    // ───────────────────────── 알림 ─────────────────────────

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "갸루렌즈 실행 상태", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_TOGGLE_AUTO), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_sparkle)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle(if (autoMode) "갸루렌즈 실시간 모드 ✨" else "갸루렌즈 대기 중 ✨")
            .setContentText("말풍선 탭: 번역 · 길게 누르기: 실시간")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, if (autoMode) "실시간 끄기" else "실시간 켜기", toggle).build())
            .addAction(Notification.Action.Builder(icon, "종료", stop).build())
            .build()
    }

    private fun updateNotification() {
        if (projection == null) return
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
        }
    }

    // ───────────────────────── 기타 ─────────────────────────

    private fun makeUiContext(): Context {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val dm = getSystemService(DisplayManager::class.java)
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    return createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }
            } catch (e: Exception) {
            }
        }
        return this
    }

    /** 실제 화면 크기(px, 현재 회전 기준) */
    private fun realSize(): Point {
        val p = Point()
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealSize(p)
        }
        if (p.x <= 0 || p.y <= 0) {
            val m = resources.displayMetrics
            p.set(m.widthPixels, m.heightPixels)
        }
        return p
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(message: String) {
        main.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }
}
