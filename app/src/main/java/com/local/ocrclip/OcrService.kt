package com.local.ocrclip

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OcrService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var recognizer: TextRecognizer
    private lateinit var worker: ExecutorService
    private lateinit var network: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private lateinit var overlay: DictionaryOverlay

    // Lets `adb shell am broadcast -a com.local.ocrclip.CAPTURE` trigger a capture for testing, since
    // adb-injected keys bypass accessibility key filtering. DUMP is held by the shell but no normal app.
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = capture()
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        worker = Executors.newSingleThreadExecutor()
        network = Executors.newSingleThreadExecutor()
        overlay = DictionaryOverlay(this)
        registerReceiver(
            captureReceiver, IntentFilter(ACTION_CAPTURE), android.Manifest.permission.DUMP, null,
            Context.RECEIVER_EXPORTED,
        )
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) unregisterReceiver(captureReceiver)
        if (::recognizer.isInitialized) recognizer.close()
        if (::worker.isInitialized) worker.shutdown()
        if (::network.isInitialized) network.shutdown()
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (bindingHotkey) return false
        val hotkey = prefs.hotkey
        if (overlay.isShowing) {
            val closes = event.keyCode == hotkey || event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B
            if (!closes) return false
            if (event.action == KeyEvent.ACTION_DOWN) overlay.hide()
            return true
        }
        if (!prefs.enabled) return false
        if (hotkey == KeyEvent.KEYCODE_UNKNOWN || event.keyCode != hotkey) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) capture()
        // Swallow both down and up so the foreground app never sees the hotkey.
        return true
    }

    private fun capture() {
        if (overlay.isShowing) {
            overlay.hide()
            return
        }
        if (busy) return
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                val buffer = shot.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                if (bitmap == null) {
                    done("Screenshot failed")
                    return
                }
                try {
                    recognize(crop(bitmap, prefs.region))
                } catch (e: Exception) {
                    done("OCR failed: ${e.message}")
                    return
                }
                // Kept only so the region picker has something to draw on.
                saveLastScreenshot(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                done(
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) "Too fast, try again"
                    else "Screenshot failed ($errorCode)"
                )
            }
        })
    }

    private fun recognize(image: Bitmap) {
        if (prefs.engine == Prefs.ENGINE_LENS) {
            network.execute {
                runCatching { LensOcr.recognize(image) }
                    .onSuccess { lines -> main.post { deliver(format(lines, prefs.keepNewlines)) } }
                    .onFailure { done("Google Lens failed: ${it.message}") }
            }
        } else {
            recognizer.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener { text -> deliver(format(mlKitLines(text), prefs.keepNewlines)) }
                .addOnFailureListener { done("OCR failed: ${it.message}") }
        }
    }

    private fun deliver(text: String) {
        if (text.isEmpty()) {
            done("No text found")
            return
        }
        if (prefs.copyToClipboard) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("OCR", text))
        }
        prefs.lastResult = text
        if (prefs.dictionaryMode != Prefs.DICT_OFF) openDictionary(text)
        done(if (prefs.showToast) text else null)
    }

    private fun openDictionary(text: String) {
        val template = prefs.dictionaryUrl
        val encoded = Uri.encode(text)
        val url = if ("{text}" in template) template.replace("{text}", encoded) else template + encoded
        if (prefs.dictionaryMode == Prefs.DICT_OVERLAY) {
            overlay.show(url)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun done(message: String?) {
        main.post {
            busy = false
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLastScreenshot(bitmap: Bitmap) {
        val file = Prefs.lastScreenshot(this)
        val tmp = java.io.File(file.path + ".tmp")
        runCatching {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(file)
        }
    }

    companion object {
        const val ACTION_CAPTURE = "com.local.ocrclip.CAPTURE"

        /** Set by MainActivity while it waits for a key press, so the old hotkey isn't swallowed. */
        @Volatile
        var bindingHotkey = false

        private val spaceBetweenCjk = Regex("""(?<=[^\x00-\x7F])\s+(?=[^\x00-\x7F])""")

        fun crop(bitmap: Bitmap, region: RectF?): Bitmap {
            if (region == null) return bitmap
            val w = bitmap.width
            val h = bitmap.height
            val left = (region.left * w).toInt().coerceIn(0, w - 1)
            val top = (region.top * h).toInt().coerceIn(0, h - 1)
            val width = ((region.right * w).toInt() - left).coerceIn(1, w - left)
            val height = ((region.bottom * h).toInt() - top).coerceIn(1, h - top)
            // ML Kit rejects tiny images; fall back to the full screen.
            if (width < 32 || height < 32) return bitmap
            return Bitmap.createBitmap(bitmap, left, top, width, height)
        }

        fun mlKitLines(text: Text): List<String> = text.textBlocks.flatMap { block -> block.lines.map { it.text } }

        /** Japanese has no word spacing, so lines are joined directly and stray OCR spaces dropped. */
        fun format(lines: List<String>, keepNewlines: Boolean): String =
            lines.asSequence()
                .map { it.replace(spaceBetweenCjk, "").trim() }
                .filter { it.isNotEmpty() }
                .joinToString(if (keepNewlines) "\n" else "")
    }
}
