package com.local.ocrclip

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A short message over the game for a few seconds, used for failures. It stands in for a toast, which Android drops
 * while the app's notifications are off. It takes neither keys nor touches, and isn't an [Overlay]: B has nothing to
 * close.
 */
class Notice(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hider = Runnable { hide() }
    private var view: TextView? = null
    private var showing = false // not isAttachedToWindow, which lags a frame behind addView and removeView

    fun show(message: String, atTop: Boolean) {
        val text = view ?: build().also { view = it }
        text.text = message
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = dp(32)
            windowAnimations = 0 // gone at once, so the next capture doesn't read it
        }
        if (showing) windowManager.updateViewLayout(text, params) else windowManager.addView(text, params)
        showing = true
        handler.removeCallbacks(hider)
        handler.postDelayed(hider, SHOW_MS)
    }

    /** Returns whether it was up. */
    fun hide(): Boolean {
        handler.removeCallbacks(hider)
        if (!showing) return false
        showing = false
        windowManager.removeView(view)
        return true
    }

    private fun build() = TextView(context).apply {
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            setColor(0xF0222222.toInt())
            setStroke(dp(1), 0xFF444444.toInt())
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val SHOW_MS = 3000L
    }
}
