package com.abdullahsolutions.kancil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KancilService : Service() {

    companion object {
        private const val TAG          = "KancilService"
        private const val CHANNEL_ID   = "kancil_service"
        const val NOTIFICATION_ID      = 1

        // Message types
        const val MSG_DESCRIBE   = 1  // client → service: run inference
        const val MSG_RESULT     = 2  // service → client: inference result
        const val MSG_ERROR      = 3  // service → client: error
        const val MSG_GET_STATUS = 4  // client → service: is model ready?
        const val MSG_STATUS     = 5  // service → client: status reply

        // Bundle keys
        const val KEY_PROMPT = "prompt"
        const val KEY_IMAGE  = "image"   // ParcelFileDescriptor
        const val KEY_RESULT = "result"
        const val KEY_READY  = "ready"
        const val KEY_ERROR  = "error"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Messenger ─────────────────────────────────────────────────────────────

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_DESCRIBE   -> handleDescribe(msg)
                MSG_GET_STATUS -> handleGetStatus(msg)
                else           -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(IncomingHandler())

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handleDescribe(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val data    = msg.data
        val prompt  = data.getString(KEY_PROMPT, "Describe this image in detail.")
        @Suppress("DEPRECATION")
        val pfd     = data.getParcelable<ParcelFileDescriptor>(KEY_IMAGE)

        scope.launch {
            try {
                if (ModelState.status.value != ModelState.Status.Ready) {
                    replyTo.send(errorMessage("model not ready"))
                    return@launch
                }

                val imageBytes: ByteArray? = pfd?.let {
                    ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() }
                }

                val result = LlamaEngine.chat(
                    messages   = listOf(ChatMessage(content = prompt, isUser = true)),
                    imageBytes = imageBytes,
                    maxTokens  = 512
                )

                val reply = Message.obtain(null, MSG_RESULT)
                reply.data = Bundle().apply { putString(KEY_RESULT, result) }
                replyTo.send(reply)
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                replyTo.send(errorMessage(e.message ?: "unknown error"))
            }
        }
    }

    private fun handleGetStatus(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val reply   = Message.obtain(null, MSG_STATUS)
        reply.data  = Bundle().apply {
            putBoolean(KEY_READY, ModelState.status.value == ModelState.Status.Ready)
        }
        replyTo.send(reply)
    }

    private fun errorMessage(text: String): Message {
        val msg = Message.obtain(null, MSG_ERROR)
        msg.data = Bundle().apply { putString(KEY_ERROR, text) }
        return msg
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        }
        scope.launch { initModel() }
    }

    private suspend fun initModel() {
        if (LlamaEngine.isLoaded) {
            ModelState.status.value = ModelState.Status.Ready
            updateNotification("Kancil AI ready")
            return
        }

        val ctx = applicationContext

        if (!ModelDownloader.isDownloaded(ctx)) {
            ModelState.status.value = ModelState.Status.Downloading(0)
            try {
                ModelDownloader.download(ctx) { pct ->
                    ModelState.status.value = ModelState.Status.Downloading(pct)
                    updateNotification("Downloading model… $pct%")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                ModelState.status.value = ModelState.Status.Error("Download failed: ${e.message}")
                updateNotification("Download failed")
                return
            }
        }

        ModelState.status.value = ModelState.Status.Loading
        updateNotification("Loading model…")

        val modelPath  = ModelDownloader.modelFile(ctx).absolutePath
        val mmprojPath = ModelDownloader.mmprojFile(ctx).absolutePath
        val nThreads   = ((Runtime.getRuntime().availableProcessors() + 1) / 2).coerceIn(2, 6)

        val ok = LlamaEngine.load(modelPath, mmprojPath, nCtx = 2048, nThreads = nThreads)
        if (ok) {
            ModelState.status.value = ModelState.Status.Ready
            updateNotification("Kancil AI ready")
        } else {
            ModelState.status.value = ModelState.Status.Error("Failed to load model")
            updateNotification("Failed to load model")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        LlamaEngine.unload()
        ModelState.status.value = ModelState.Status.Idle
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Kancil AI Service", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the AI model ready in the background" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kancil")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
