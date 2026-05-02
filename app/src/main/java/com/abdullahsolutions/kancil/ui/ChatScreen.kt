package com.abdullahsolutions.kancil.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abdullahsolutions.kancil.ChatMessage
import com.abdullahsolutions.kancil.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val appState        by vm.appState.collectAsState()
    val messages        by vm.messages.collectAsState()
    val isGenerating    by vm.isGenerating.collectAsState()
    val partialReply    by vm.partialReply.collectAsState()
    val pendingImage    by vm.pendingImage.collectAsState()
    val webSearchEnabled by vm.webSearchEnabled.collectAsState()
    val isSearching     by vm.isSearching.collectAsState()

    when (val state = appState) {
        is ChatViewModel.AppState.CheckingModel -> FullScreenMessage("Checking model…")
        is ChatViewModel.AppState.Downloading   -> DownloadScreen(state.progress)
        is ChatViewModel.AppState.LoadingModel  -> FullScreenMessage("Loading model into memory…")
        is ChatViewModel.AppState.Error         -> ErrorScreen(state.msg) { vm.retryInit() }
        is ChatViewModel.AppState.Ready         -> ChatContent(
            messages         = messages,
            isGenerating     = isGenerating,
            partialReply     = partialReply,
            pendingImage     = pendingImage,
            webSearchEnabled = webSearchEnabled,
            isSearching      = isSearching,
            onImagePick      = { vm.setPendingImage(it) },
            onToggleSearch   = { vm.toggleWebSearch() },
            onSend           = vm::sendMessage
        )
    }
}

// ── Chat content ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    partialReply: String,
    pendingImage: Uri?,
    webSearchEnabled: Boolean,
    isSearching: Boolean,
    onImagePick: (Uri?) -> Unit,
    onToggleSearch: () -> Unit,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> onImagePick(uri) }

    LaunchedEffect(messages.size, partialReply) {
        val count = messages.size + if (partialReply.isNotEmpty()) 1 else 0
        if (count > 0) scope.launch { listState.animateScrollToItem(count - 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kancil") },
                actions = {
                    IconButton(onClick = onToggleSearch) {
                        Icon(
                            imageVector = if (webSearchEnabled) Icons.Default.Search
                                          else Icons.Default.SearchOff,
                            contentDescription = if (webSearchEnabled) "Web search on" else "Web search off",
                            tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        // Let the scaffold ignore IME and nav bar — we handle both manually below
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()   // ← clears system nav bar at bottom
                .imePadding()              // ← pushes content above keyboard
        ) {
            // ── Message list ─────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (partialReply.isNotEmpty()) {
                    item(key = "partial") {
                        MessageBubble(
                            ChatMessage(content = partialReply, isUser = false),
                            isStreaming = true
                        )
                    }
                }
                if (isSearching) {
                    item(key = "searching") { StatusIndicator("Searching the web…") }
                } else if (isGenerating && partialReply.isEmpty()) {
                    item(key = "thinking") { ThinkingIndicator() }
                }
            }

            // ── Pending image preview ─────────────────────────────────────────
            if (pendingImage != null) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .size(80.dp)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(pendingImage).crossfade(true).build(),
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                    IconButton(
                        onClick = { onImagePick(null) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                RoundedCornerShape(50)
                            )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove image",
                             modifier = Modifier.size(14.dp))
                    }
                }
            }

            // ── Input bar ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {

                // Image attach button
                IconButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !isGenerating
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = "Attach image",
                        tint = if (pendingImage != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        if (!isGenerating && input.isNotBlank()) {
                            onSend(input); input = ""
                        }
                    }),
                    enabled = !isGenerating,
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(Modifier.width(6.dp))

                FilledIconButton(
                    onClick = {
                        if (!isGenerating && input.isNotBlank()) {
                            onSend(input); input = ""
                        }
                    },
                    enabled = !isGenerating && input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(msg: ChatMessage, isStreaming: Boolean = false) {
    val align   = if (msg.isUser) Alignment.End else Alignment.Start
    val bgColor = if (msg.isUser) MaterialTheme.colorScheme.primaryContainer
                  else            MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        // Show attached image above the text bubble
        if (msg.isUser && msg.imageUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(msg.imageUri).crossfade(true).build(),
                contentDescription = "Attached image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .padding(bottom = 4.dp)
            )
        }

        if (msg.content.isNotEmpty() || isStreaming) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (msg.isUser) 16.dp else 4.dp,
                            bottomEnd   = if (msg.isUser) 4.dp  else 16.dp
                        )
                    )
                    .background(bgColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (msg.isUser) {
                    Text(
                        text  = msg.content + if (isStreaming) "▌" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    MarkdownText(
                        text  = msg.content + if (isStreaming) "▌" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ── Status indicators ─────────────────────────────────────────────────────────

@Composable
private fun StatusIndicator(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
                                     bottomEnd = 16.dp, bottomStart = 4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label,
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "dots")
    val dots by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = LinearEasing), RepeatMode.Restart
        ), label = "dots"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
                                     bottomEnd = 16.dp, bottomStart = 4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text("Thinking" + ".".repeat(dots.toInt() + 1),
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Helper screens ────────────────────────────────────────────────────────────

@Composable
private fun DownloadScreen(progress: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Downloading model…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("$progress%", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("This happens only once (~4.4 GB: model + vision projector).",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FullScreenMessage(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(msg, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ErrorScreen(msg: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Error", style = MaterialTheme.typography.titleLarge,
             color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
