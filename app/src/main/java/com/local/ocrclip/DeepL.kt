package com.local.ocrclip

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** DeepL API v2 text translation. Free-plan keys end in ":fx" and use their own host. */
object DeepL {
    /** Target languages the API accepts, as (code, name). */
    val TARGETS = listOf(
        "AR" to "Arabic", "BG" to "Bulgarian", "CS" to "Czech", "DA" to "Danish", "DE" to "German",
        "EL" to "Greek", "EN-GB" to "English (UK)", "EN-US" to "English (US)", "ES" to "Spanish",
        "ES-419" to "Spanish (Latin America)", "ET" to "Estonian", "FI" to "Finnish", "FR" to "French",
        "HE" to "Hebrew", "HU" to "Hungarian", "ID" to "Indonesian", "IT" to "Italian", "KO" to "Korean",
        "LT" to "Lithuanian", "LV" to "Latvian", "NB" to "Norwegian (Bokmål)", "NL" to "Dutch", "PL" to "Polish",
        "PT-BR" to "Portuguese (Brazil)", "PT-PT" to "Portuguese (Portugal)", "RO" to "Romanian",
        "RU" to "Russian", "SK" to "Slovak", "SL" to "Slovenian", "SV" to "Swedish", "TH" to "Thai",
        "TR" to "Turkish", "UK" to "Ukrainian", "VI" to "Vietnamese", "ZH-HANS" to "Chinese (simplified)",
        "ZH-HANT" to "Chinese (traditional)",
    )

    /** Returns the translation of Japanese [text] into [target] (e.g. "EN-US"). Blocking; call off the main thread. */
    fun translate(text: String, key: String, target: String): String {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", "JA")
            .put("target_lang", target)
            .toString().toByteArray()
        val conn = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            when (val code = conn.responseCode) {
                200 -> {}
                403 -> throw IOException("API key rejected")
                456 -> throw IOException("monthly quota used up")
                429 -> throw IOException("too many requests")
                else -> throw IOException("HTTP $code")
            }
            val response = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
            return response.getJSONArray("translations").getJSONObject(0).getString("text")
        } finally {
            conn.disconnect()
        }
    }
}
