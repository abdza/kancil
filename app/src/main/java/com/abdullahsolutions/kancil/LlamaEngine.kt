package com.abdullahsolutions.kancil

import android.util.Log

object LlamaEngine {

    private const val TAG    = "LlamaEngine"
    // The media marker mtmd uses to locate where the image embedding goes in the prompt
    private const val MEDIA_MARKER = "<__media__>"

    var isLoaded: Boolean = false
        private set

    init { System.loadLibrary("llama-android") }

    fun load(modelPath: String, mmprojPath: String, nCtx: Int = 4096, nThreads: Int = 4): Boolean {
        if (isLoaded) return true
        Log.i(TAG, "Loading model=$modelPath  mmproj=$mmprojPath")
        return loadModel(modelPath, mmprojPath, nCtx, nThreads).also { isLoaded = it }
    }

    @Synchronized
    fun chat(
        messages: List<ChatMessage>,
        imageBytes: ByteArray? = null,   // bytes of the image attached to the last user turn
        searchContext: String? = null,   // web search results to inject as context
        maxTokens: Int = 512,
        onToken: ((String) -> Unit)? = null
    ): String {
        if (onToken != null) {
            setTokenCallback(object : TokenCallback {
                override fun onToken(token: String) = onToken(token)
            })
        }
        val prompt = buildGemmaPrompt(messages, hasImage = imageBytes != null, searchContext = searchContext)
        return generateWithImage(prompt, imageBytes, maxTokens)
    }

    fun unload() { freeModel(); isLoaded = false }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private fun buildGemmaPrompt(
        messages: List<ChatMessage>,
        hasImage: Boolean,
        searchContext: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("<bos>")
        val lastUserIndex = messages.indexOfLast { it.isUser }
        for ((index, msg) in messages.withIndex()) {
            val role = if (msg.isUser) "user" else "model"
            sb.append("<start_of_turn>$role\n")
            // Inject web search results before the last user message
            if (msg.isUser && index == lastUserIndex && searchContext != null) {
                sb.append("Here are relevant web search results:\n\n")
                sb.append(searchContext)
                sb.append("\nUsing the above results where relevant, answer the following:\n\n")
            }
            // Insert media marker for images
            if (msg.isUser && hasImage && index == lastUserIndex) {
                sb.append(MEDIA_MARKER).append("\n")
            }
            sb.append(msg.content)
            sb.append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    private external fun loadModel(
        path: String, mmprojPath: String, nCtx: Int, nThreads: Int
    ): Boolean

    private external fun setTokenCallback(callback: TokenCallback)

    private external fun generateWithImage(
        prompt: String, imageBytes: ByteArray?, maxTokens: Int
    ): String

    private external fun freeModel()

    interface TokenCallback {
        fun onToken(token: String)
    }
}
