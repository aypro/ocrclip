package com.local.ocrclip

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Settings screen colours, Material 3 dark roles on a pure black ground (the Retroids' OLED switches those pixels
 * off). Android 12+ takes them from the system's wallpaper palette; older versions get a fixed blue-teal one.
 */
class Palette(context: Context) {
    private val dynamic = Build.VERSION.SDK_INT >= 31
    private val c = context

    private fun pick(system: Int, fallback: Long) = if (dynamic) c.getColor(system) else fallback.toInt()

    val bg = Color.BLACK
    val card = pick(android.R.color.system_neutral1_900, 0xFF1A2024)
    val cardHigh = pick(android.R.color.system_neutral1_800, 0xFF2A3135)
    val on = pick(android.R.color.system_neutral1_100, 0xFFDEE3E7)
    val onVar = pick(android.R.color.system_neutral2_200, 0xFFBFC8CE)
    val outline = pick(android.R.color.system_neutral2_400, 0xFF89929A)
    val outlineVar = pick(android.R.color.system_neutral2_700, 0xFF3F484E)
    val primary = pick(android.R.color.system_accent1_200, 0xFF8ACEF5)
    val onPrimary = pick(android.R.color.system_accent1_800, 0xFF00344A)
    val pc = pick(android.R.color.system_accent1_700, 0xFF004C69)
    val onPc = pick(android.R.color.system_accent1_100, 0xFFC4E7FF)
    val sc = pick(android.R.color.system_accent2_700, 0xFF344A56)
    val onSc = pick(android.R.color.system_accent2_100, 0xFFD0E6F4)
    val error = 0xFFFFB4AB.toInt()
    val onError = 0xFF690005.toInt()
    val ec = 0xFF93000A.toInt()
    val onEc = 0xFFFFDAD6.toInt()
}

/** Where a row sits in a group of cards: groups get large outer corners and small inner ones. */
enum class Pos { SINGLE, TOP, MIDDLE, BOTTOM }

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.radii(pos: Pos, outer: Int = 20, inner: Int = 4): FloatArray {
    val o = dp(outer.toFloat())
    val i = dp(inner.toFloat())
    val (top, bottom) = when (pos) {
        Pos.SINGLE -> o to o
        Pos.TOP -> o to i
        Pos.MIDDLE -> i to i
        Pos.BOTTOM -> i to o
    }
    return floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
}

fun Context.radiiAll(r: Int): FloatArray = FloatArray(8) { dp(r.toFloat()) }

fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

fun shape(radii: FloatArray, fill: Int, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = radii
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

/** Background for anything you can press: a ripple for touch, and a primary ring while the D-pad has it focused. */
fun Context.tappable(p: Palette, radii: FloatArray, fill: Int, stroke: Int = 0, strokeWidth: Int = 0): Drawable {
    val states = StateListDrawable().apply {
        val focused = LayerDrawable(
            arrayOf(shape(radii, fill, stroke, strokeWidth), shape(radii, Color.TRANSPARENT, p.primary, dpi(3))),
        )
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), shape(radii, fill, stroke, strokeWidth))
    }
    return RippleDrawable(ColorStateList.valueOf(withAlpha(p.on, 0x29)), states, shape(radii, Color.WHITE))
}

fun Context.label(size: Float, color: Int, medium: Boolean = false, value: CharSequence? = null): TextView =
    TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (value != null) text = value
    }

/** Lets TalkBack announce a custom row as a switch, radio button or checkbox with its current state. */
fun View.announceAs(className: String, checked: () -> Boolean) {
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = className
            info.isCheckable = true
            info.isChecked = checked()
        }
    }
}

/**
 * Material 3 switch, radio button or checkbox, drawn rather than themed. It only shows state: the row it sits in
 * takes the touch or D-pad press, so the whole row is the target.
 */
class Indicator(context: Context, private val p: Palette, private val kind: Kind) : View(context) {
    enum class Kind { SWITCH, RADIO, CHECK }

    var checked = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = when (kind) {
            Kind.SWITCH -> 52 to 32
            Kind.RADIO -> 20 to 20
            Kind.CHECK -> 18 to 18
        }
        setMeasuredDimension((w * d).roundToInt(), (h * d).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            Kind.SWITCH -> {
                val r = h / 2
                fill(if (checked) p.primary else p.cardHigh)
                canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
                if (!checked) {
                    stroke(p.outline, 2 * d)
                    canvas.drawRoundRect(d, d, w - d, h - d, r - d, r - d, paint)
                }
                val cx = if (checked) w - r else r
                fill(if (checked) p.onPrimary else p.outline)
                canvas.drawCircle(cx, r, (if (checked) 12 else 8) * d, paint)
                if (checked) drawCheck(canvas, cx, r, 16 * d, p.onPc, 2.6f * d)
            }
            Kind.RADIO -> {
                stroke(if (checked) p.primary else p.onVar, 2 * d)
                canvas.drawCircle(w / 2, h / 2, w / 2 - d, paint)
                if (checked) {
                    fill(p.primary)
                    canvas.drawCircle(w / 2, h / 2, 5 * d, paint)
                }
            }
            Kind.CHECK -> {
                if (checked) {
                    fill(p.primary)
                    canvas.drawRoundRect(0f, 0f, w, h, 2 * d, 2 * d, paint)
                    drawCheck(canvas, w / 2, h / 2, 14 * d, p.onPrimary, 3f * d)
                } else {
                    stroke(p.onVar, 2 * d)
                    canvas.drawRoundRect(d, d, w - d, h - d, 2 * d, 2 * d, paint)
                }
            }
        }
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
    }

    /** The ic_check path (M5 12.5 l4.5 4.5 L19 7.5 in a 24 box), centred on [cx], [cy] and [box] wide. */
    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, box: Float, color: Int, width: Float) {
        val s = box / 24f
        path.reset()
        path.moveTo(cx + (5f - 12f) * s, cy + (12.5f - 12f) * s)
        path.lineTo(cx + (9.5f - 12f) * s, cy + (17f - 12f) * s)
        path.lineTo(cx + (19f - 12f) * s, cy + (7.5f - 12f) * s)
        stroke(color, width)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
    }
}

/**
 * Small 4:3 picture of the screen: the last screenshot, if there is one, with the capture region drawn on top.
 * With [popup], it shows where the translation popup appears instead (the edge away from the region).
 */
class RegionPreview(context: Context, private val p: Palette, private val popup: Boolean) : View(context) {
    var region: RectF? = null
        set(v) {
            field = v
            invalidate()
        }

    var screenshot: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((96 * d).roundToInt(), (72 * d).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, 10 * d, 10 * d, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(p.cardHigh)
        screenshot?.let {
            box.set(0f, 0f, w, h)
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            canvas.drawBitmap(it, null, box, paint)
            canvas.drawColor(0x66000000)
        }
        val r = region ?: RectF(0f, 0f, 1f, 1f)
        box.set(r.left * w, r.top * h, r.right * w, r.bottom * h)
        if (popup) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = p.outline
            paint.pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
            if (region != null) canvas.drawRoundRect(box, 4 * d, 4 * d, paint)
            paint.pathEffect = null
            // Same rule as OcrService: the popup goes to the top unless the region's centre is in the top half.
            val atTop = (region?.centerY() ?: 1f) > 0.5f
            val top = if (atTop) 0.08f * h else 0.74f * h
            box.set(0.08f * w, top, 0.92f * w, top + 0.18f * h)
            paint.style = Paint.Style.FILL
            paint.color = p.primary
            canvas.drawRoundRect(box, 4 * d, 4 * d, paint)
        } else {
            box.inset(d, d)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(p.primary, 0x2E)
            canvas.drawRoundRect(box, 4 * d, 4 * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.color = p.primary
            canvas.drawRoundRect(box, 4 * d, 4 * d, paint)
        }
        canvas.restore()
    }
}
