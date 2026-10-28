package com.local.ocrclip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/** A window the service shows over the game and closes on B, Back or a hotkey. */
interface Overlay {
    val isShowing: Boolean
    fun hide()
}

/**
 * Web page (dictionary, translator or the offline lookup page) shown in an accessibility overlay window on top of the
 * game, so the game stays in front. The overlay takes key focus while open, so gamepad input goes to the page, not the
 * game. The service keeps one instance per page so each keeps its own loaded site. [bridge] is exposed to the page as
 * `OCRClip`; only the offline lookup overlay has one, since it never loads a website.
 */
class DictionaryOverlay(context: Context, private val bridge: Any? = null) : Overlay {
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
        attach(view)
    }

    /** Shows a page built in the app instead of a website. */
    fun showPage(html: String) {
        val view = root ?: build().also { root = it }
        val web = webView!!
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        attach(view)
        // The page handles the D-pad itself, so it needs key focus.
        web.requestFocus()
    }

    private fun attach(view: FrameLayout) {
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
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Lets the self-hosted jisho switch to a compact layout (no site header, search box at the bottom).
            settings.userAgentString = "${settings.userAgentString} OCRClip/1"
            webViewClient = WebViewClient() // keep link clicks inside the overlay
            setBackgroundColor(Color.BLACK)
            bridge?.let { addJavascriptInterface(it, "OCRClip") }
        }
        webView = web
        return FrameLayout(context).apply {
            // Dimmed backdrop; tapping it closes the overlay, as do B, Back and the hotkeys.
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { hide() }
            addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { setMargins(margin, margin, margin, margin) })
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()
}
