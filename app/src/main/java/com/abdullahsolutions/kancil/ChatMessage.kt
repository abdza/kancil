package com.abdullahsolutions.kancil

import android.net.Uri

data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val imageUri: Uri? = null,          // attached image (user messages only)
    val id: Long = System.nanoTime()
)
