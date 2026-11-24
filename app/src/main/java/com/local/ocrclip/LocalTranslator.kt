package com.local.ocrclip

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Offline Japanese → English translation with a CAT-Translate GGUF (CyberAgent's translation model), run on the CPU by
 * llama.cpp through libllm.so (app/src/main/cpp). The model loads on the first translation, a few seconds for the
 * 3.3B, and is freed after [IDLE_MINUTES] without one, since it holds several GB while loaded.
 */
class LocalTranslator(private val context: Context) {
    /** Gets the translation's text so far; returning false stops it. */
    fun interface Progress {
        fun onText(text: String): Boolean
    }

    private val executor = Executors.newSingleThreadScheduledExecutor()

    // Touched only on the executor's thread.
    private var handle = 0L
    private var loadedStamp: Pair<Long, Long>? = null
    private var idle: ScheduledFuture<*>? = null

    val available get() = file(context).exists() && supported

    /**
     * Translates [text] on the translator's thread: [loading] runs before the model has to be loaded, [progress] gets
     * the growing translation and [done] the outcome (null when it finished or was stopped). All on that thread.
     */
    fun translate(text: String, loading: () -> Unit, progress: Progress, done: (Throwable?) -> Unit) {
        executor.execute {
            idle?.cancel(false)
            val failure = runCatching {
                val session = session(loading)
                val output = StringBuilder()
                val error = nativeGenerate(session, prompt(text).toByteArray(), NativeProgress { bytes ->
                    output.append(bytes.decodeToString())
                    progress.onText(clean(output))
                })
                if (error != null) throw IOException(error)
                if (clean(output).isEmpty()) throw IOException("no translation")
            }.exceptionOrNull()
            idle = executor.schedule(::unload, IDLE_MINUTES, TimeUnit.MINUTES)
            done(failure)
        }
    }

    /** Frees the model and stops the thread. */
    fun close() {
        executor.execute(::unload)
        executor.shutdown()
    }

    /** The loaded model, (re)loaded if the file changed since. */
    private fun session(loading: () -> Unit): Long {
        val file = file(context)
        val stamp = file.lastModified() to file.length()
        if (handle != 0L && stamp == loadedStamp) return handle
        unload()
        if (!file.exists()) throw IOException("no model loaded")
        if (!supported) throw IOException("this CPU can't run it")
        loading()
        handle = nativeLoad(file.path, THREADS, CONTEXT)
        if (handle == 0L) throw IOException("the model couldn't be loaded")
        loadedStamp = stamp
        return handle
    }

    private fun unload() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
        loadedStamp = null
    }

    private fun interface NativeProgress {
        fun onText(bytes: ByteArray): Boolean
    }

    companion object {
        /** Outside backups: it's a few GB. */
        fun file(context: Context) = File(context.noBackupFilesDir, "translate.gguf")

        private const val IDLE_MINUTES = 10L

        // The Nova's four fastest cores; more threads were slower (they wait on the efficiency cores).
        private const val THREADS = 4

        // Prompt plus translation in tokens: a full text box is a few hundred.
        private const val CONTEXT = 1024

        /**
         * libllm.so is built for ARMv8.2 with the dot-product instructions; a CPU without them would crash the
         * service, so it's only loaded where /proc/cpuinfo lists them.
         */
        val supported: Boolean by lazy {
            val dotProduct = runCatching { File("/proc/cpuinfo").readText().contains(" asimddp") }.getOrDefault(false)
            dotProduct && runCatching { System.loadLibrary("llm") }.isSuccess
        }

        /** CAT-Translate's chat format; the tokenizer adds <s> in front. */
        private fun prompt(text: String) =
            "<|user|>Translate the following Japanese text into English.\n\n$text</s><|assistant|>"

        /** The beta checkpoints sometimes write their end-of-text marker as text instead of ending. */
        private fun clean(output: CharSequence) = output.toString().substringBefore("</s>").trim()

        /** Throws, with a message for the user, unless [file] is a CAT-Translate GGUF this build can run. */
        fun validate(file: File) {
            if (!supported) throw IOException("this device's CPU can't run it")
            val template = nativeTemplate(file.path)?.decodeToString()
                ?: throw IOException("it isn't a GGUF model file")
            if ("<|user|>" !in template || "<|assistant|>" !in template) {
                throw IOException("it isn't a CAT-Translate model")
            }
        }

        @JvmStatic private external fun nativeTemplate(path: String): ByteArray?
        @JvmStatic private external fun nativeLoad(path: String, threads: Int, context: Int): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic
        private external fun nativeGenerate(handle: Long, prompt: ByteArray, progress: NativeProgress): String?
    }
}
