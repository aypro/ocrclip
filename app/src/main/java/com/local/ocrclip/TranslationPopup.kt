package com.local.ocrclip

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Compact accessibility overlay with a DeepL translation above its source text, at the top or bottom edge
 * of the screen. Unlike [DictionaryOverlay] it never takes key focus, so the game keeps the gamepad; the
 * service's key filter still closes it on B, Back or a hotkey. Touches outside it reach the game and close it.
 */
class TranslationPopup(context: Context) : Overlay {
    private val context = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private lateinit var source: TextView
    private lateinit var translation: TextView

    override val isShowing get() = root?.isAttachedToWindow == true

    /** Shows [text] with a placeholder until [setTranslation] fills in the result. */
    fun show(text: String, atTop: Boolean) {
        val view = root ?: build().also { root = it }
        source.text = text
        translation.text = "…"
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = if (atTop) Gravity.TOP else Gravity.BOTTOM }
        if (isShowing) windowManager.updateViewLayout(view, params) else windowManager.addView(view, params)
    }

    fun setTranslation(text: String) {
        translation.text = text
    }

    override fun hide() {
        if (isShowing) windowManager.removeView(root)
    }

    private fun build(): View {
        val padding = dp(12)
        val maxHeight = context.resources.displayMetrics.heightPixels * 45 / 100
        source = TextView(context).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
            setTextColor(0xFFAAAAAA.toInt())
        }
        translation = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(translation)
            addView(source)
        }
        // Long passages scroll instead of covering more than about half the game.
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }.apply {
            background = GradientDrawable().apply {
                setColor(0xF5202020.toInt())
                cornerRadius = dp(8).toFloat()
            }
            addView(content)
            setOnClickListener { hide() }
        }
        content.setOnClickListener { hide() }
        return LinearLayout(context).apply {
            setPadding(padding, padding, padding, padding)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            setOnClickListener { hide() }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) hide()
                false
            }
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()
}
