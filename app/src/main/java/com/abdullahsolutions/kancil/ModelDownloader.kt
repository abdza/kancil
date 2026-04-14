package com.abdullahsolutions.kancil

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ModelDownloader {

    private const val TAG = "ModelDownloader"

    private const val MODEL_URL =
        "https://huggingface.co/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/google_gemma-4-E2B-it-Q4_0.gguf"
    private const val MMPROJ_URL =
        "https://huggingface.co/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/mmproj-google_gemma-4-E2B-it-f16.gguf"

    const val MODEL_FILENAME   = "google_gemma-4-E2B-it-Q4_0.gguf"
    const val MMPROJ_FILENAME  = "mmproj-google_gemma-4-E2B-it-f16.gguf"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun modelFile  (context: Context): File = File(context.filesDir, MODEL_FILENAME)
    fun mmprojFile (context: Context): File = File(context.filesDir, MMPROJ_FILENAME)

    fun isDownloaded(context: Context): Boolean =
        modelFile(context).exists() && mmprojFile(context).exists()

    /**
     * Download both the text model and the mmproj file.
     * [onProgress] is called with 0..100 across both downloads combined.
     */
    suspend fun download(
        context: Context,
        onProgress: (Int) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        // Model is ~3.4 GB, mmproj is ~1 GB — weight progress accordingly
        val modelWeight  = 77  // ~77% of total bytes
        val mmprojWeight = 23

        if (!modelFile(context).exists()) {
            downloadFile(MODEL_URL, modelFile(context)) { pct ->
                onProgress((pct * modelWeight) / 100)
            }
        } else {
            onProgress(modelWeight)
        }

        if (!mmprojFile(context).exists()) {
            downloadFile(MMPROJ_URL, mmprojFile(context)) { pct ->
                onProgress(modelWeight + (pct * mmprojWeight) / 100)
            }
        }

        onProgress(100)
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val tmp = File(dest.parent, "${dest.name}.tmp")

        val request  = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: ${response.message} — $url")
        }

        val body   = response.body ?: throw RuntimeException("Empty response body")
        val total  = body.contentLength()
        var written = 0L

        body.byteStream().use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(8 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    written += read
                    if (total > 0) onProgress(((written * 100) / total).toInt())
                }
            }
        }

        tmp.renameTo(dest)
        Log.i(TAG, "Saved ${dest.name}")
    }
}
