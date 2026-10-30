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
    private lateinit var dictionary: DictionaryOverlay
    private lateinit var translator: DictionaryOverlay
    private lateinit var localDictionary: LocalDictionary
    private lateinit var lookup: DictionaryOverlay
    private lateinit var popup: TranslationPopup
    private val openOverlay get() = listOf(dictionary, translator, lookup, popup).firstOrNull { it.isShowing }

    /** Bumped per DeepL request, so a late answer for a closed or replaced popup is dropped. */
    private var translation = 0

    /** What a capture does with the text once it's recognised. */
    private enum class Action { LOOKUP, TRANSLATE }

    // Lets `adb shell am broadcast -a com.local.ocrclip.CAPTURE [--es action translate]` trigger a capture for
    // testing, since adb-injected keys bypass accessibility key filtering. DUMP is held by the shell but no normal app.
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) =
            capture(if (intent.getStringExtra("action") == "translate") Action.TRANSLATE else Action.LOOKUP)
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        worker = Executors.newSingleThreadExecutor()
        network = Executors.newSingleThreadExecutor()
        dictionary = DictionaryOverlay(this)
        translator = DictionaryOverlay(this)
        localDictionary = LocalDictionary(this)
        lookup = DictionaryOverlay(this, localDictionary)
        popup = TranslationPopup(this)
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
        if (::dictionary.isInitialized) dictionary.destroy()
        if (::translator.isInitialized) translator.destroy()
        if (::lookup.isInitialized) lookup.destroy()
        if (::localDictionary.isInitialized) localDictionary.close()
        if (::popup.isInitialized) popup.hide()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (bindingHotkey) return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_UNKNOWN) return false
        val action = when (code) {
            prefs.hotkey -> Action.LOOKUP
            prefs.translateHotkey -> Action.TRANSLATE
            else -> null
        }
        openOverlay?.let { open ->
            val closes = action != null || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B
            if (!closes) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                // After following links in the lookup page, B and Back step back through them before closing it.
                if (open === lookup && action == null && localDictionary.backSteps > 0) lookup.run("back()") else open.hide()
            }
            return true
        }
        if (!prefs.enabled || action == null) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) capture(action)
        // Swallow both down and up so the foreground app never sees the hotkey.
        return true
    }

    private fun capture(action: Action) {
        openOverlay?.let {
            it.hide()
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
                    recognize(crop(bitmap, prefs.region), action)
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

    private fun recognize(image: Bitmap, action: Action) {
        if (prefs.engine == Prefs.ENGINE_LENS) {
            network.execute {
                runCatching { LensOcr.recognize(image) }
                    .onSuccess { lines -> main.post { deliver(format(lines, prefs.keepNewlines), action) } }
                    .onFailure { done("Google Lens failed: ${it.message}") }
            }
        } else {
            recognizer.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener { text -> deliver(format(mlKitLines(text), prefs.keepNewlines), action) }
                .addOnFailureListener { done("OCR failed: ${it.message}") }
        }
    }

    private fun deliver(text: String, action: Action) {
        if (text.isEmpty()) {
            done("No text found")
            return
        }
        if (prefs.copyToClipboard) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("OCR", text))
        }
        prefs.lastResult = text
        when (action) {
            Action.LOOKUP -> if (prefs.dictionaryMode != Prefs.DICT_OFF) openDictionary(text)
            // The translator always opens over the game; that's the point of its separate hotkey.
            Action.TRANSLATE -> translate(text)
        }
        done(if (prefs.showToast) text else null)
    }

    /** DeepL API popup when a key is set, otherwise (or if the API fails) the translator page. */
    private fun translate(text: String) {
        val key = prefs.deeplKey
        if (key.isEmpty()) {
            translator.show(Prefs.fillTemplate(prefs.translateUrl, text))
            return
        }
        // Keep the popup off the captured text: below it when the region is in the top half, otherwise above.
        popup.show(text, atTop = (prefs.region?.centerY() ?: 1f) > 0.5f)
        val request = ++translation
        val target = prefs.deeplTarget
        network.execute {
            val result = runCatching { DeepL.translate(text, key, target) }
            main.post {
                if (request != translation || !popup.isShowing) return@post
                result.onSuccess { popup.setTranslation(it) }.onFailure {
                    popup.hide()
                    Toast.makeText(this, "DeepL failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    translator.show(Prefs.fillTemplate(prefs.translateUrl, text))
                }
            }
        }
    }

    private fun openDictionary(text: String) {
        if (prefs.dictionaryMode == Prefs.DICT_LOCAL) {
            localDictionary.backSteps = 0
            lookup.showPage(LookupPage.page(text))
            return
        }
        val url = Prefs.fillTemplate(prefs.dictionaryUrl, text)
        if (prefs.dictionaryMode == Prefs.DICT_OVERLAY) {
            dictionary.show(url)
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
