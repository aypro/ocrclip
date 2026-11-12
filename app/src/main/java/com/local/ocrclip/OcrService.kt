package com.local.ocrclip

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OcrService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var recognizer: TextRecognizer
    private lateinit var worker: ExecutorService
    private lateinit var network: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private lateinit var localDictionary: LocalDictionary
    private lateinit var anki: Anki
    private lateinit var displays: DisplayManager

    /** Overlays over the game. Only these take keys, so only these close on B, Back or a hotkey. */
    private lateinit var game: Screen

    /** Overlays on the second screen while it's in use, and the display they're on. */
    private var panel: Screen? = null
    private var panelDisplay = Display.INVALID_DISPLAY

    /** The text the lookup page shows, for the Sentence field of Anki notes. */
    @Volatile private var lookupText = ""
    private val openOverlay get() = game.open

    /**
     * The overlays of one screen: over the game, or on the second screen [display]. The service keeps one of each so
     * every page keeps its own loaded site.
     *
     * On the second screen, windows are added through the service's display context: only its WindowManager carries
     * the accessibility overlay token for that display. That context keeps the game screen's configuration though
     * (1280x960 window bounds), and views built with it lay out, scroll and draw as if the add-on were that size. So
     * the views get a window context of the display instead, which has its real configuration and follows changes to
     * it. Nothing is added through that one: its own token isn't an accessibility service's.
     */
    private inner class Screen(display: Display?) {
        val panel = display != null
        private val windows: WindowManager
        private val views: Context

        init {
            if (display == null) {
                views = this@OcrService
                windows = getSystemService(WindowManager::class.java)
            } else {
                val service = createDisplayContext(display)
                windows = service.getSystemService(WindowManager::class.java)
                views = service.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            }
        }

        // On the second screen a black window stays under the pages. Android mirrors the game onto a display with
        // nothing on it, so without this the add-on would show the game until the first capture and after ✕.
        // Tapping it (around the DeepL popup) clears the screen; it never takes focus, so the game keeps the keys.
        private val blank = if (panel) View(views).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener { made.forEach { it.hide() } }
            val size = displaySize(windows)
            windows.addView(this, WindowManager.LayoutParams(
                size.x, size.y,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE,
            ))
        } else null

        private val made = mutableListOf<Overlay>()
        private fun <T : Overlay> make(create: () -> T) = lazy { create().also { made += it } }

        val dictionary by make { DictionaryOverlay(views, panel = panel, windowManager = windows) }
        val translator by make { DictionaryOverlay(views, panel = panel, windowManager = windows) }
        val lookup by make { DictionaryOverlay(views, localDictionary, ::lookupFile, panel, windows) }
        val popup by make { TranslationPopup(views, panel, windows) }
        val open get() = made.firstOrNull { it.isShowing }

        /** Hides the others, which would stay up under [overlay] on the second screen (captures don't close them). */
        fun only(overlay: Overlay) = made.forEach { if (it !== overlay) it.hide() }

        fun destroy() {
            made.forEach { if (it is DictionaryOverlay) it.destroy() else it.hide() }
            blank?.let { if (it.isAttachedToWindow) windows.removeView(it) }
        }
    }

    // A WebView can't outlive its display, so the panel goes when the add-on is unplugged.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            screen()
        }

        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == panelDisplay) dropPanel()
        }
    }

    // Turning the second screen on or off in the settings blanks or frees it right away.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "second_screen") screen()
    }

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
        localDictionary = LocalDictionary(this)
        game = Screen(null)
        anki = Anki(this)
        displays = getSystemService(DisplayManager::class.java)
        displays.registerDisplayListener(displayListener, main)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        screen() // blanks an add-on that's already attached
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
        if (::displays.isInitialized) displays.unregisterDisplayListener(displayListener)
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::game.isInitialized) game.destroy()
        dropPanel()
        if (::localDictionary.isInitialized) localDictionary.close()
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
                if (open === game.lookup && action == null && localDictionary.backSteps > 0) game.lookup.run("back()") else open.hide()
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
                // For the region picker and Anki notes. Saved first, so it's in place when the lookup shows.
                saveLastScreenshot(bitmap)
                try {
                    recognize(crop(bitmap, prefs.region), action)
                } catch (e: Exception) {
                    done("OCR failed: ${e.message}")
                }
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
        val screen = screen()
        val translator = screen.translator
        val popup = screen.popup
        val key = prefs.deeplKey
        if (key.isEmpty()) {
            screen.only(translator)
            translator.show(Prefs.fillTemplate(prefs.translateUrl, text))
            return
        }
        // Keep the popup off the captured text: below it when the region is in the top half, otherwise above.
        // Nothing to keep clear on the second screen, so it starts at the top there.
        screen.only(popup)
        popup.show(text, atTop = screen.panel || (prefs.region?.centerY() ?: 1f) > 0.5f)
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
        val screen = screen()
        if (prefs.dictionaryMode == Prefs.DICT_LOCAL) {
            val lookup = screen.lookup
            screen.only(lookup)
            runCatching { Prefs.lastScreenshot(this).copyTo(Prefs.lookupScreenshot(this), overwrite = true) }
            localDictionary.backSteps = 0
            lookupText = text
            val ankiReady = prefs.ankiModel >= 0 && anki.installed && anki.permitted
            localDictionary.anki = if (ankiReady) ::addToAnki else null
            localDictionary.ankiExisting = if (ankiReady) ::existingInAnki else null
            val font = prefs.lookupFont
            val fontVersion = font?.let { "${it.name.hashCode()}-${it.lastModified()}" }
            lookup.showPage(LookupPage.page(text, fontVersion, panel = screen.panel))
            return
        }
        screen.only(screen.dictionary)
        screen.dictionary.show(Prefs.fillTemplate(prefs.dictionaryUrl, text))
    }

    /**
     * Where lookups go: the second screen when it's wanted and attached, otherwise over the game. Also sets up or
     * drops the second screen's overlays to match.
     */
    private fun screen(): Screen {
        val display = if (prefs.secondScreen) {
            // The presentation category leaves out private virtual displays, such as a screen recording's.
            displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        } else null
        if (display == null) {
            dropPanel()
            return game
        }
        panel?.let { if (panelDisplay == display.displayId) return it }
        dropPanel()
        return Screen(display).also {
            panel = it
            panelDisplay = display.displayId
        }
    }

    private fun dropPanel() {
        panel?.destroy()
        panel = null
        panelDisplay = Display.INVALID_DISPLAY
    }

    /** Files the lookup page may load by path: only its font. */
    private fun lookupFile(path: String): File? = if (path == "/font") prefs.lookupFont else null

    /**
     * Adds a note for [definition] of [entry] to AnkiDroid, with the screenshot of the capture the lookup shows.
     * Runs on the lookup page's bridge thread. Returns an error or null.
     */
    private fun addToAnki(entry: LocalDictionary.Entry, definition: LocalDictionary.Definition, offset: Int): String? {
        val error = try {
            val model = anki.model(prefs.ankiModel)
            if (model == null) {
                "The Anki note type is gone; pick one in OCR Clip's settings"
            } else {
                val shot = Prefs.lookupScreenshot(this)
                val picture = if (AnkiNote.wantsPicture(model.fields) && shot.exists()) {
                    anki.addMedia(ShareProvider.screenshot, "ocrclip_${entry.term}")
                } else null
                val fields = AnkiNote.fields(model.fields, AnkiNote.Source(
                    entry, definition, lookupText, offset, picture, localDictionary.styles,
                ))
                val deck = prefs.ankiDeck.takeIf { it >= 0 } ?: Anki.DEFAULT_DECK
                anki.addNote(model.id, deck, fields, prefs.ankiTags.split(' ').filter { it.isNotBlank() })
                null
            }
        } catch (e: SecurityException) {
            "Allow OCR Clip to use AnkiDroid in its settings"
        } catch (e: Exception) {
            "Anki: ${e.message}"
        }
        error?.let { main.post { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }
        return error
    }

    /** Which of [terms] already have a note of the chosen type; empty if AnkiDroid can't be asked. */
    private fun existingInAnki(terms: Collection<String>): Set<String> = try {
        anki.model(prefs.ankiModel)?.let { anki.existing(it.id, AnkiNote.termField(it.fields), terms) } ?: emptySet()
    } catch (e: Exception) {
        emptySet()
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
