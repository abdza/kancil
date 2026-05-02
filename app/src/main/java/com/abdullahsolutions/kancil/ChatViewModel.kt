package com.abdullahsolutions.kancil

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "ChatViewModel"

    sealed class AppState {
        object CheckingModel                      : AppState()
        data class Downloading(val progress: Int) : AppState()
        object LoadingModel                       : AppState()
        object Ready                              : AppState()
        data class Error(val msg: String)         : AppState()
    }

    private val _appState     = MutableStateFlow<AppState>(AppState.CheckingModel)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _messages     = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _partialReply = MutableStateFlow("")
    val partialReply: StateFlow<String> = _partialReply.asStateFlow()

    private val _pendingImage = MutableStateFlow<Uri?>(null)
    val pendingImage: StateFlow<Uri?> = _pendingImage.asStateFlow()

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    fun toggleWebSearch() { _webSearchEnabled.value = !_webSearchEnabled.value }

    init {
        // Mirror ModelState into AppState so the UI stays in sync with the service
        viewModelScope.launch {
            ModelState.status.collect { status ->
                _appState.value = when (status) {
                    ModelState.Status.Idle                  -> AppState.CheckingModel
                    is ModelState.Status.Downloading        -> AppState.Downloading(status.progress)
                    ModelState.Status.Loading               -> AppState.LoadingModel
                    ModelState.Status.Ready                 -> AppState.Ready
                    is ModelState.Status.Error              -> AppState.Error(status.msg)
                }
            }
        }
    }

    fun setPendingImage(uri: Uri?) { _pendingImage.value = uri }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        val imageUri = _pendingImage.value
        _pendingImage.value = null

        val userMsg = ChatMessage(content = text.trim(), isUser = true, imageUri = imageUri)
        _messages.update { it + userMsg }
        _isGenerating.value = true
        _partialReply.value = ""

        val appContext = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imageBytes: ByteArray? = imageUri?.let { uri ->
                    try {
                        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read image bytes", e)
                        null
                    }
                }

                val searchContext: String? = if (_webSearchEnabled.value) {
                    _isSearching.value = true
                    try {
                        WebSearcher.search(text.trim())
                    } finally {
                        _isSearching.value = false
                    }
                } else null

                LlamaEngine.chat(
                    messages      = _messages.value,
                    imageBytes    = imageBytes,
                    searchContext = searchContext,
                    maxTokens     = 512,
                    onToken       = { token -> _partialReply.update { it + token } }
                )
                val reply = _partialReply.value.stripLlmArtifacts()
                _messages.update { it + ChatMessage(content = reply, isUser = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                _messages.update {
                    it + ChatMessage(content = "[Error: ${e.message}]", isUser = false)
                }
            } finally {
                _isGenerating.value = false
                _partialReply.value = ""
            }
        }
    }

    fun retryInit() {
        val ctx = getApplication<Application>()
        ModelDownloader.modelFile(ctx).delete()
        ModelDownloader.mmprojFile(ctx).delete()
        ModelState.status.value = ModelState.Status.Idle
        ctx.stopService(Intent(ctx, KancilService::class.java))
        ctx.startForegroundService(Intent(ctx, KancilService::class.java))
    }
}

private fun String.stripLlmArtifacts(): String = this
    .replace("<end_of_turn>", "")
    .replace("<start_of_turn>model\n", "")
    .replace("<start_of_turn>user\n", "")
    .replace("<start_of_turn>", "")
    .replace("<|end|>", "")
    .replace("<|endoftext|>", "")
    .replace("<bos>", "")
    .replace("<eos>", "")
    .trim()
