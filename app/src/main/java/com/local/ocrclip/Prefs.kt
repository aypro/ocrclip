package com.local.ocrclip

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.view.KeyEvent
import java.io.File

class Prefs(context: Context) {
    val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var hotkey: Int
        get() = sp.getInt("hotkey", KeyEvent.KEYCODE_UNKNOWN)
        set(v) = sp.edit().putInt("hotkey", v).apply()

    /** Optional second hotkey: OCR, then show the translator overlay. KEYCODE_UNKNOWN when unset. */
    var translateHotkey: Int
        get() = sp.getInt("translate_hotkey", KeyEvent.KEYCODE_UNKNOWN)
        set(v) = sp.edit().putInt("translate_hotkey", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var engine: String
        get() = sp.getString("engine", ENGINE_LOCAL) ?: ENGINE_LOCAL
        set(v) = sp.edit().putString("engine", v).apply()

    var keepNewlines: Boolean
        get() = sp.getBoolean("keep_newlines", false)
        set(v) = sp.edit().putBoolean("keep_newlines", v).apply()

    var copyToClipboard: Boolean
        get() = sp.getBoolean("copy_to_clipboard", true)
        set(v) = sp.edit().putBoolean("copy_to_clipboard", v).apply()

    var showToast: Boolean
        get() = sp.getBoolean("show_toast", true)
        set(v) = sp.edit().putBoolean("show_toast", v).apply()

    /** What happens after a capture: DICT_OFF, DICT_OVERLAY, DICT_LOCAL or DICT_BROWSER. */
    var dictionaryMode: String
        get() = sp.getString("dictionary_mode", null)
            // Older builds only had an on/off switch that opened the browser.
            ?: if (sp.getBoolean("open_dictionary", false)) DICT_BROWSER else DICT_OFF
        set(v) = sp.edit().putString("dictionary_mode", v).remove("open_dictionary").apply()

    /** `{text}` is replaced with the URL-encoded result; without it the text is appended. */
    var dictionaryUrl: String
        get() = sp.getString("dictionary_url", DEFAULT_DICTIONARY_URL) ?: DEFAULT_DICTIONARY_URL
        set(v) = sp.edit().putString("dictionary_url", v).apply()

    /**
     * Local dictionary titles in display order; ones not listed follow in build order. Kept by title, not in the
     * database, so a database rebuilt on the PC keeps the user's order.
     */
    var dictionaryOrder: List<String>
        get() = (sp.getString("dictionary_order", "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("dictionary_order", v.joinToString("\n")).apply()

    /** Titles of local dictionaries the user turned off. */
    var disabledDictionaries: Set<String>
        get() = sp.getStringSet("disabled_dictionaries", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("disabled_dictionaries", v).apply()

    /** Same `{text}` rules as [dictionaryUrl]; used by the translate hotkey. */
    var translateUrl: String
        get() = sp.getString("translate_url", DEFAULT_TRANSLATE_URL) ?: DEFAULT_TRANSLATE_URL
        set(v) = sp.edit().putString("translate_url", v).apply()

    /** DeepL API key for the translate popup; empty means use the translator page instead. */
    var deeplKey: String
        get() = sp.getString("deepl_key", "") ?: ""
        set(v) = sp.edit().putString("deepl_key", v).apply()

    /** DeepL target language code, e.g. EN-US, EN-GB or DE. */
    var deeplTarget: String
        get() = sp.getString("deepl_target", DEFAULT_DEEPL_TARGET) ?: DEFAULT_DEEPL_TARGET
        set(v) = sp.edit().putString("deepl_target", v).apply()

    var lastResult: String
        get() = sp.getString("last_result", "") ?: ""
        set(v) = sp.edit().putString("last_result", v).apply()

    /** Capture region as fractions (0..1) of the screen, or null for full screen. */
    var region: RectF?
        get() = if (!sp.contains("region_l")) null else RectF(
            sp.getFloat("region_l", 0f), sp.getFloat("region_t", 0f),
            sp.getFloat("region_r", 1f), sp.getFloat("region_b", 1f),
        )
        set(v) {
            val e = sp.edit()
            if (v == null) {
                e.remove("region_l").remove("region_t").remove("region_r").remove("region_b")
            } else {
                e.putFloat("region_l", v.left).putFloat("region_t", v.top)
                    .putFloat("region_r", v.right).putFloat("region_b", v.bottom)
            }
            e.apply()
        }

    companion object {
        const val ENGINE_LOCAL = "local"
        const val ENGINE_LENS = "lens"
        const val DICT_OFF = "off"
        const val DICT_OVERLAY = "overlay"
        const val DICT_LOCAL = "local"
        const val DICT_BROWSER = "browser"
        const val DEFAULT_DICTIONARY_URL = "https://jisho.hlorenzi.com/search/{text}"
        const val DEFAULT_TRANSLATE_URL = "https://www.deepl.com/translator#ja/en/{text}"
        const val DEFAULT_DEEPL_TARGET = "EN-US"

        fun lastScreenshot(context: Context) = File(context.cacheDir, "last_screenshot.jpg")

        /** `{text}` in [template] is replaced with the URL-encoded text; without it the text is appended. */
        fun fillTemplate(template: String, text: String): String {
            val encoded = Uri.encode(text)
            return if ("{text}" in template) template.replace("{text}", encoded) else template + encoded
        }

        fun keyName(code: Int): String =
            if (code == KeyEvent.KEYCODE_UNKNOWN) "not set"
            else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }
}
