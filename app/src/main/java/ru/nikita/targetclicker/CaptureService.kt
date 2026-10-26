package ru.nikita.targetclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Захватывает экран и ищет на каждом кадре зелёную кнопку
 * (центр масс зелёных пикселей внутри области поиска).
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "capture"
        private const val SCALE = 2          // кадр в 2 раза меньше экрана — быстрее
        private const val STEP = 2           // проверяем каждый 2-й пиксель
        private const val MIN_PIXELS = 60    // меньше — считаем, что цели нет
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null

    private var capW = 0
    private var capH = 0

    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null || projection != null) return START_NOT_STICKY

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = p
        setupCapture(p)
        TargetState.captureActive = true
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Захват экрана", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("TargetClicker следит за целью")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Стоп", stopIntent).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun setupCapture(p: MediaProjection) {
        val (w, h) = screenSize()
        capW = w / SCALE
        capH = h / SCALE

        val t = HandlerThread("capture").apply { start() }
        thread = t
        val handler = Handler(t.looper)

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try { analyze(img) } finally { img.close() }
        }, handler)
        reader = r

        display = p.createVirtualDisplay(
            "TargetClicker", capW, capH,
            resources.displayMetrics.densityDpi / SCALE,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    private fun analyze(img: Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride

        val y0 = (capH * Config.topPct / 100).coerceIn(0, capH - 1)
        val y1 = (capH * Config.bottomPct / 100).coerceIn(y0 + 1, capH)

        var sx = 0L
        var sy = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val row = y * rs
            var x = 0
            while (x < capW) {
                val i = row + x * ps
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                // зелёный цвет кнопки (~ #3DCB7D) на сером фоне
                if (g > 140 && g - r > 60 && g - b > 30) {
                    sx += x; sy += y; n++
                }
                x += STEP
            }
            y += STEP
        }

        val now = SystemClock.uptimeMillis()
        if (n < MIN_PIXELS) {
            TargetState.found = false
            hasLast = false
            return
        }

        val cx = sx.toFloat() / n * SCALE
        val cy = sy.toFloat() / n * SCALE

        if (hasLast) {
            val dt = (now - lastT).toFloat()
            if (dt in 1f..300f) {
                // сглаживаем скорость, чтобы не дёргалось
                TargetState.vx = TargetState.vx * 0.6f + (cx - lastX) / dt * 0.4f
                TargetState.vy = TargetState.vy * 0.6f + (cy - lastY) / dt * 0.4f
            }
        } else {
            TargetState.vx = 0f
            TargetState.vy = 0f
        }
        lastX = cx; lastY = cy; lastT = now; hasLast = true

        TargetState.x = cx
        TargetState.y = cy
        TargetState.time = now
        TargetState.found = true
    }

    override fun onDestroy() {
        TargetState.captureActive = false
        TargetState.found = false
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        display = null; reader = null; projection = null; thread = null
        super.onDestroy()
    }
}
