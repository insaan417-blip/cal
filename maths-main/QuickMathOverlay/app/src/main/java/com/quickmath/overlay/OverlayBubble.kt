package com.quickmath.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.hypot

/**
 * The floating answer bubble (WindowManager + SYSTEM_ALERT_WINDOW).
 * Drag to move. Tap to minimize to a dot; tap the dot to expand again.
 */
class OverlayBubble(private val context: Context, private val prefs: AppPrefs) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val view = TextView(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )

    private var shown = false
    private var minimized = false
    private var answer = "Ready"
    private val hideRunnable = Runnable { view.visibility = View.INVISIBLE }

    private fun dp(v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    fun show() {
        if (shown) return
        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.bubbleX
        params.y = prefs.bubbleY
        applyStyle()
        view.setOnTouchListener(DragListener())
        try {
            wm.addView(view, params)
            shown = true
        } catch (e: Exception) {
            MonitorState.setMessage("Could not show the bubble: allow \"Display over other apps\".")
        }
    }

    /** Re-reads size and opacity from settings. Call on the main thread. */
    fun applyStyle() {
        val alpha = prefs.opacityPct.coerceIn(20, 100) * 255 / 100
        val bg = GradientDrawable()
        bg.setColor(Color.argb(alpha, 17, 24, 39))
        bg.cornerRadius = dp(40f).toFloat()
        bg.setStroke(dp(1f), Color.argb(alpha, 56, 189, 248))
        view.background = bg
        view.setTextColor(Color.WHITE)
        view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.CENTER
        view.setSingleLine(true)

        if (minimized) {
            view.text = "\u2022"
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            view.minWidth = dp(34f)
            view.minHeight = dp(34f)
            view.setPadding(dp(6f), 0, dp(6f), 0)
        } else {
            val size = prefs.bubbleSizeSp.coerceIn(12, 60).toFloat()
            // Very long answers shrink a little so the bubble stays small.
            val shrink = if (answer.length > 10) 10f / answer.length else 1f
            view.text = answer
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(12f, size * maxOf(0.5f, shrink)))
            view.minWidth = dp(size * 1.6f)
            view.minHeight = dp(size * 1.5f)
            view.setPadding(dp(size * 0.6f), dp(size * 0.25f), dp(size * 0.6f), dp(size * 0.25f))
        }
        if (shown) {
            try {
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                // view already gone
            }
        }
    }

    fun setAnswer(text: String) {
        answer = text
        handler.removeCallbacks(hideRunnable)
        view.visibility = View.VISIBLE
        applyStyle()
        val sec = prefs.displaySec
        if (sec > 0 && !minimized) handler.postDelayed(hideRunnable, sec * 1000L)
    }

    private fun toggleMinimized() {
        minimized = !minimized
        applyStyle()
    }

    fun remove() {
        handler.removeCallbacks(hideRunnable)
        if (shown) {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                // already removed
            }
            shown = false
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > touchSlop) moved = true
                    if (moved) {
                        params.x = maxOf(0, startX + dx.toInt())
                        params.y = maxOf(0, startY + dy.toInt())
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (ex: Exception) {
                            // view gone
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.bubbleX = params.x
                        prefs.bubbleY = params.y
                    } else {
                        toggleMinimized()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }
}
