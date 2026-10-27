package com.local.ocrclip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

/** A window the service shows over the game and closes on B, Back or a hotkey. */
interface Overlay {
    val isShowing: Boolean
    fun hide()
}

/**
 * Web page (dictionary or translator) shown in an accessibility overlay window on top of the game, so the
 * game stays in front. The overlay takes key focus while open, so gamepad input goes to the page, not the game.
 * The service keeps one instance per page so each keeps its own loaded site.
 */
class DictionaryOverlay(context: Context) : Overlay {
    private val context = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var webView: WebView? = null

    override val isShowing get() = root?.isAttachedToWindow == true

    fun show(url: String) {
        val view = root ?: build().also { root = it }
        val web = webView!!
        val current = web.url
        if ('#' in url && current != null && current.substringBefore('#') == url.substringBefore('#')) {
            // DeepL keeps the text in the fragment. A fragment-only change doesn't reload the page and a
            // single-page app may ignore it, so force a full load at the new URL.
            web.evaluateJavascript("location.replace(${JSONObject.quote(url)}); location.reload();", null)
        } else {
            web.loadUrl(url)
        }
        if (!isShowing) {
            windowManager.addView(view, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT,
            ))
        }
    }

    override fun hide() {
        if (isShowing) windowManager.removeView(root)
    }

    fun destroy() {
        hide()
        webView?.destroy()
        webView = null
        root = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(): FrameLayout {
        val margin = dp(16)
        // Space above the page for the close button, so it doesn't cover the page content.
        val closeSize = dp(40)
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Lets the self-hosted jisho switch to a compact layout (no site header, search box at the bottom).
            settings.userAgentString = "${settings.userAgentString} OCRClip/1"
            webViewClient = WebViewClient() // keep link clicks inside the overlay
            setBackgroundColor(Color.BLACK)
        }
        webView = web
        val close = TextView(context).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC333333.toInt())
            setOnClickListener { hide() }
        }
        return FrameLayout(context).apply {
            // Dimmed backdrop; tapping it closes the overlay.
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { hide() }
            addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { setMargins(margin, closeSize, margin, margin) })
            addView(close, FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP or Gravity.END).apply {
                setMargins(0, 0, margin, 0)
            })
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()
}
