package com.local.ocrclip

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Shows the last screenshot; drag a rectangle over the text area to OCR. */
class RegionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val bitmap = BitmapFactory.decodeFile(Prefs.lastScreenshot(this).path)
        if (bitmap == null) {
            finish()
            return
        }
        val regionView = RegionView(this, bitmap, prefs.region)

        val hint = TextView(this).apply {
            text = "Drag over the text area"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 12, 24, 12)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Cancel"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Save"
                setOnClickListener {
                    regionView.selection?.let { prefs.region = it }
                    finish()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(regionView)
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ))
            addView(buttons, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ))
        })
    }
}

class RegionView(context: Context, private val bitmap: Bitmap, initial: RectF?) : View(context) {
    /** Selection as fractions of the bitmap, or null if nothing selected yet. */
    var selection: RectF? = initial
        private set

    private val dst = RectF()
    private var startX = 0f
    private var startY = 0f
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, dst, null)
        val sel = selection ?: return
        val r = RectF(
            dst.left + sel.left * dst.width(), dst.top + sel.top * dst.height(),
            dst.left + sel.right * dst.width(), dst.top + sel.bottom * dst.height(),
        )
        canvas.drawRect(dst.left, dst.top, dst.right, r.top, dim)
        canvas.drawRect(dst.left, r.bottom, dst.right, dst.bottom, dim)
        canvas.drawRect(dst.left, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, dst.right, r.bottom, dim)
        canvas.drawRect(r, border)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = ((event.x - dst.left) / dst.width()).coerceIn(0f, 1f)
        val y = ((event.y - dst.top) / dst.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                selection = RectF(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                invalidate()
            }
        }
        return true
    }
}
