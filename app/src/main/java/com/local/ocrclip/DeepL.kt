package com.local.ocrclip

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** DeepL API v2 text translation. Free-plan keys end in ":fx" and use their own host. */
object DeepL {
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
