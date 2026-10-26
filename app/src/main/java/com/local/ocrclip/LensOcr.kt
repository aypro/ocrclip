package com.local.ocrclip

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlin.math.sqrt

/**
 * Google Lens OCR through the endpoint Chrome's Lens overlay uses (same request as owocr's "glens"
 * engine). Unofficial: Google can change or block it at any time. The protobuf messages are
 * hand-encoded here; field numbers come from Chromium's lens_overlay_*.proto files.
 */
object LensOcr {
    private const val ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
    // Chrome's public API key, as used by owocr.
    private const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
    private const val MAX_PIXELS = 3_000_000
    private const val PLATFORM_WEB = 3L
    private const val SURFACE_CHROMIUM = 4L
    private const val AUTO_FILTER = 7L

    private val random = SecureRandom()

    /** Returns the recognised lines, top to bottom. Blocking; call off the main thread. */
    fun recognize(source: Bitmap): List<String> {
        val image = downscale(source)
        val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val response = post(buildRequest(png, image.width, image.height))
        return parseLines(response)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val pixels = bitmap.width.toLong() * bitmap.height
        if (pixels <= MAX_PIXELS) return bitmap
        val scale = sqrt(MAX_PIXELS.toDouble() / pixels)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private fun buildRequest(png: ByteArray, width: Int, height: Int): ByteArray {
        val analyticsId = ByteArray(16).also { random.nextBytes(it) }
        val requestId = Proto()
            .varint(1, random.nextLong()) // uuid
            .varint(2, 0) // sequence_id
            .varint(3, 0) // image_sequence_id
            .bytes(4, analyticsId)
        val locale = Proto().string(1, "ja").string(2, "Asia/Tokyo")
        val filters = Proto().message(1, Proto().varint(1, AUTO_FILTER))
        val clientContext = Proto()
            .varint(1, PLATFORM_WEB)
            .varint(2, SURFACE_CHROMIUM)
            .message(4, locale)
            .message(17, filters)
        val requestContext = Proto().message(3, requestId).message(4, clientContext)
        val imageData = Proto()
            .message(1, Proto().bytes(1, png)) // payload.image_bytes
            .message(3, Proto().varint(1, width.toLong()).varint(2, height.toLong())) // image_metadata
        val objectsRequest = Proto().message(1, requestContext).message(3, imageData)
        return Proto().message(1, objectsRequest).toByteArray()
    }

    private fun post(body: ByteArray): ByteArray {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-protobuf")
            conn.setRequestProperty("X-Goog-Api-Key", API_KEY)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** objects_response(2).text(3).text_layout(1).paragraphs(1).lines(2).words(1).{plain_text(2), text_separator(3)} */
    private fun parseLines(response: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        for (objects in ProtoReader.messages(response, 2))
            for (text in ProtoReader.messages(objects, 3))
                for (layout in ProtoReader.messages(text, 1))
                    for (paragraph in ProtoReader.messages(layout, 1))
                        for (line in ProtoReader.messages(paragraph, 2)) {
                            val sb = StringBuilder()
                            for (word in ProtoReader.messages(line, 1)) {
                                ProtoReader.strings(word, 2).firstOrNull()?.let(sb::append)
                                ProtoReader.strings(word, 3).firstOrNull()?.let(sb::append)
                            }
                            lines += sb.toString()
                        }
        return lines
    }
}

/** Just enough protobuf writing for the Lens request. */
private class Proto {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply {
        writeVarint((field shl 3).toLong())
        writeVarint(value)
    }

    fun bytes(field: Int, value: ByteArray) = apply {
        writeVarint((field shl 3 or 2).toLong())
        writeVarint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray())

    fun message(field: Int, value: Proto) = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeVarint(v: Long) {
        var value = v
        while (value and 0x7FL.inv() != 0L) {
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
        out.write(value.toInt())
    }
}

/** Just enough protobuf reading to pull length-delimited fields out of the Lens response. */
private object ProtoReader {
    fun messages(data: ByteArray, field: Int): List<ByteArray> = lengthDelimited(data, field)

    fun strings(data: ByteArray, field: Int): List<String> = lengthDelimited(data, field).map { String(it) }

    private fun lengthDelimited(data: ByteArray, field: Int): List<ByteArray> {
        val found = mutableListOf<ByteArray>()
        var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < data.size) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> {
                    val len = readVarint().toInt()
                    if (number == field) found += data.copyOfRange(pos, pos + len)
                    pos += len
                }
                5 -> pos += 4
                else -> throw IOException("Unsupported protobuf wire type in Lens response")
            }
        }
        return found
    }
}
