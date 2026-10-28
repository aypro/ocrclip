package com.local.ocrclip

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var localDictionary: LocalDictionary
    private lateinit var serviceStatus: TextView
    private lateinit var hotkeyLabel: TextView
    private lateinit var translateHotkeyLabel: TextView
    private lateinit var regionLabel: TextView
    private lateinit var lastResult: TextView

    /** Which hotkey the next key press binds, or null when not binding. */
    private var binding: Binding? = null

    private enum class Binding { LOOKUP, TRANSLATE }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        localDictionary = LocalDictionary(this)
        serviceStatus = findViewById(R.id.serviceStatus)
        hotkeyLabel = findViewById(R.id.hotkeyLabel)
        translateHotkeyLabel = findViewById(R.id.translateHotkeyLabel)
        regionLabel = findViewById(R.id.regionLabel)
        lastResult = findViewById(R.id.lastResult)

        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.setHotkey).setOnClickListener { startBinding(Binding.LOOKUP) }
        findViewById<Button>(R.id.setTranslateHotkey).setOnClickListener { startBinding(Binding.TRANSLATE) }
        findViewById<Button>(R.id.clearTranslateHotkey).setOnClickListener {
            prefs.translateHotkey = KeyEvent.KEYCODE_UNKNOWN
        }
        findViewById<Button>(R.id.pickRegion).setOnClickListener {
            if (Prefs.lastScreenshot(this).exists()) {
                startActivity(Intent(this, RegionActivity::class.java))
            } else {
                Toast.makeText(this, "Press the hotkey in your game once first, then pick the region on that screenshot.", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.clearRegion).setOnClickListener { prefs.region = null }

        findViewById<RadioGroup>(R.id.engine).apply {
            check(if (prefs.engine == Prefs.ENGINE_LENS) R.id.engineLens else R.id.engineLocal)
            setOnCheckedChangeListener { _, id ->
                prefs.engine = if (id == R.id.engineLens) Prefs.ENGINE_LENS else Prefs.ENGINE_LOCAL
            }
        }

        bindSwitch(R.id.enabled, prefs.enabled) { prefs.enabled = it }
        bindSwitch(R.id.keepNewlines, prefs.keepNewlines) { prefs.keepNewlines = it }
        bindSwitch(R.id.copyToClipboard, prefs.copyToClipboard) { prefs.copyToClipboard = it }
        bindSwitch(R.id.showToast, prefs.showToast) { prefs.showToast = it }
        val dictModes = mapOf(
            R.id.dictOff to Prefs.DICT_OFF, R.id.dictOverlay to Prefs.DICT_OVERLAY, R.id.dictLocal to Prefs.DICT_LOCAL,
            R.id.dictBrowser to Prefs.DICT_BROWSER,
        )
        findViewById<RadioGroup>(R.id.dictionaryMode).apply {
            check(dictModes.entries.first { it.value == prefs.dictionaryMode }.key)
            setOnCheckedChangeListener { _, id -> prefs.dictionaryMode = dictModes.getValue(id) }
        }
        bindText(R.id.dictionaryUrl, prefs.dictionaryUrl) {
            prefs.dictionaryUrl = it.ifEmpty { Prefs.DEFAULT_DICTIONARY_URL }
        }
        findViewById<Button>(R.id.loadDictionary).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_DATABASE,
            )
        }
        bindText(R.id.translateUrl, prefs.translateUrl) {
            prefs.translateUrl = it.ifEmpty { Prefs.DEFAULT_TRANSLATE_URL }
        }
        bindText(R.id.deeplKey, prefs.deeplKey) { prefs.deeplKey = it }
        bindText(R.id.deeplTarget, prefs.deeplTarget) {
            prefs.deeplTarget = it.uppercase().ifEmpty { Prefs.DEFAULT_DEEPL_TARGET }
        }
    }

    private fun bindText(id: Int, initial: String, onChange: (String) -> Unit) {
        findViewById<EditText>(id).apply {
            setText(initial)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) = onChange(s.toString().trim())
            })
        }
    }

    private fun startBinding(target: Binding) {
        binding = target
        OcrService.bindingHotkey = true
        refresh()
    }

    private fun stopBinding() {
        binding = null
        OcrService.bindingHotkey = false
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val s = findViewById<Switch>(id)
        s.isChecked = initial
        s.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
        showDictionaries()
    }

    override fun onPause() {
        super.onPause()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopBinding()
        localDictionary.close()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != PICK_DATABASE || resultCode != RESULT_OK || uri == null) return
        Toast.makeText(this, "Copying the dictionary database…", Toast.LENGTH_LONG).show()
        // A database with several dictionaries can be hundreds of MB, so copy it off the main thread.
        Thread {
            val target = LocalDictionary.file(this)
            val tmp = File(target.path + ".tmp")
            val failure = runCatching {
                contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                LocalDictionary.validate(tmp)
                if (!tmp.renameTo(target)) error("couldn't replace the old database")
            }.exceptionOrNull()
            tmp.delete()
            runOnUiThread {
                val message = failure?.let { "Couldn't load it: ${it.message}" } ?: "Dictionary database loaded"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                showDictionaries()
            }
        }.start()
    }

    /** Status line and one row per dictionary: on/off and ▲▼ to change the display order. */
    private fun showDictionaries() {
        val status = findViewById<TextView>(R.id.dictionaryStatus)
        val list = findViewById<LinearLayout>(R.id.dictionaryList)
        list.removeAllViews()
        val dictionaries = localDictionary.dictionaries()
        if (dictionaries == null) {
            status.text = localDictionary.error?.let { "Dictionary database: can't be opened, $it." }
                ?: ("Dictionary database: none loaded. Build one on a PC from Yomitan dictionaries with " +
                    "tools/build_dict.py, copy it to this device, then load it here.")
            return
        }
        val size = LocalDictionary.file(this).length() / 1_000_000
        status.text = "Dictionary database: ${dictionaries.size} dictionaries, $size MB. Checked ones are used, " +
            "in this order."
        dictionaries.forEachIndexed { i, d ->
            val counts = listOf(d.terms to "terms", d.meta to "frequency/pitch", d.kanji to "kanji")
                .filter { it.first > 0 }.joinToString(", ") { "%,d %s".format(it.first, it.second) }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(CheckBox(this).apply {
                text = "${d.title}\n$counts"
                isChecked = d.enabled
                setOnCheckedChangeListener { _, on ->
                    prefs.disabledDictionaries =
                        if (on) prefs.disabledDictionaries - d.title else prefs.disabledDictionaries + d.title
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            for ((label, by) in listOf("▲" to -1, "▼" to 1)) {
                row.addView(Button(this).apply {
                    text = label
                    isEnabled = i + by in dictionaries.indices
                    setOnClickListener {
                        val titles = dictionaries.map { it.title }.toMutableList()
                        Collections.swap(titles, i, i + by)
                        prefs.dictionaryOrder = titles
                        showDictionaries()
                    }
                })
            }
            list.addView(row)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = binding ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            val other = if (target == Binding.LOOKUP) prefs.translateHotkey else prefs.hotkey
            when {
                code == KeyEvent.KEYCODE_BACK -> {}
                code == other ->
                    Toast.makeText(this, "${Prefs.keyName(code)} is already the other hotkey", Toast.LENGTH_SHORT).show()
                target == Binding.LOOKUP -> prefs.hotkey = code
                else -> prefs.translateHotkey = code
            }
            stopBinding()
            refresh()
        }
        return true
    }

    private fun refresh() {
        val enabled = ServiceEnabler.ensureEnabled(this)
        serviceStatus.text = (if (enabled) "Accessibility service: on"
        else "Accessibility service: OFF. Turn on \"${getString(R.string.service_label)}\" in accessibility settings.") +
            (if (ServiceEnabler.canEnable(this)) "\nAuto re-enable after reboot: on"
            else "\nAuto re-enable after reboot: off (needs a one-time adb grant)")

        val waiting = "Press the button you want to use… (Back cancels)"
        hotkeyLabel.text = if (binding == Binding.LOOKUP) waiting
        else "Hotkey: ${Prefs.keyName(prefs.hotkey)}\nOther apps won't receive this button while the hotkey is active."
        translateHotkeyLabel.text = if (binding == Binding.TRANSLATE) waiting
        else "Translate hotkey: ${Prefs.keyName(prefs.translateHotkey)}\nReads the text like the hotkey, then shows " +
            "the DeepL translation in a small popup (with an API key) or the translator page over the game. " +
            "B, Back or either hotkey closes it."

        regionLabel.text = prefs.region?.let {
            "Region: %d%%–%d%% across, %d%%–%d%% down".format(
                (it.left * 100).toInt(), (it.right * 100).toInt(), (it.top * 100).toInt(), (it.bottom * 100).toInt(),
            )
        } ?: "Region: full screen"

        lastResult.text = prefs.lastResult.ifEmpty { "–" }
    }

    companion object {
        private const val PICK_DATABASE = 1
    }
}
