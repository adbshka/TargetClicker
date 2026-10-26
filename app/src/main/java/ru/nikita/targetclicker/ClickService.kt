package ru.nikita.targetclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Кликает по цели и показывает плавающую кнопку ▶/■.
 * Кнопку можно перетаскивать пальцем.
 */
class ClickService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var button: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val loopRunnable = Runnable { loop() }

    override fun onServiceConnected() {
        Config.load(this)
        showButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopClicking()
        button?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        button = null
        super.onDestroy()
    }

    // ---------- клики ----------

    private fun toggle() {
        if (TargetState.clicking) {
            stopClicking()
        } else {
            if (!TargetState.captureActive) {
                Toast.makeText(this, "Сначала включи захват экрана в приложении", Toast.LENGTH_SHORT).show()
                return
            }
            TargetState.clicking = true
            updateButton()
            loop()
        }
    }

    private fun stopClicking() {
        TargetState.clicking = false
        handler.removeCallbacks(loopRunnable)
        updateButton()
    }

    private fun loop() {
        if (!TargetState.clicking) return
        if (!TargetState.captureActive) { stopClicking(); return }

        val now = SystemClock.uptimeMillis()
        val t = TargetState
        if (t.found && now - t.time < 300) {
            // упреждение: куда цель сдвинется к моменту клика
            val ahead = (now - t.time + Config.leadMs).toFloat()
            val x = (t.x + t.vx * ahead).coerceAtLeast(0f)
            val y = (t.y + t.vy * ahead).coerceAtLeast(0f)
            tap(x, y)
        } else {
            handler.postDelayed(loopRunnable, 16)
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        val next = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                handler.postDelayed(loopRunnable, Config.intervalMs)
            }
            override fun onCancelled(g: GestureDescription?) {
                handler.postDelayed(loopRunnable, Config.intervalMs)
            }
        }
        if (!dispatchGesture(gesture, next, handler)) {
            handler.postDelayed(loopRunnable, 50)
        }
    }

    // ---------- плавающая кнопка ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (56 * resources.displayMetrics.density).toInt()

        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        button = tv
        updateButton()

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = resources.displayMetrics.heightPixels / 4
        }
        params = lp

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
        wm.addView(tv, lp)
    }

    private fun updateButton() {
        val tv = button ?: return
        tv.text = if (TargetState.clicking) "■" else "▶"
        // не зелёная, чтобы кнопка сама не считалась целью
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (TargetState.clicking) Color.parseColor("#D32F2F") else Color.parseColor("#455A64"))
        }
    }
}
