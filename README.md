# Kancil

An Android app that runs a multimodal AI model (Google Gemma 4 2B) fully on-device using [llama.cpp](https://github.com/ggerganov/llama.cpp). No cloud, no API keys — inference happens entirely on your phone.

## Features

- **On-device AI chat** — text and image input, streaming token output
- **Multimodal** — attach a photo and ask questions about it
- **Web search** — optional DuckDuckGo search to ground answers in current information
- **Local AI service** — exposes inference to other apps on the same device via Android Messenger IPC

## How it works

On first launch Kancil downloads two model files (~4.4 GB total) from Hugging Face:

| File | Size |
|---|---|
| `google_gemma-4-E2B-it-Q4_0.gguf` | ~3.4 GB |
| `mmproj-google_gemma-4-E2B-it-f16.gguf` | ~1 GB |

Once downloaded the model is loaded into memory and Kancil runs as a **foreground service**, keeping the model warm so responses are instant.

## AI service for other apps

Other apps on the same device can bind to `KancilService` and use the model without going online. The API uses Android's `Messenger` / `Bundle` IPC — no HTTP, no ports, no special permissions on the client side.

### Detecting if Kancil is running

```kotlin
val intent = Intent().apply {
    component = ComponentName(
        "com.abdullahsolutions.kancil",
        "com.abdullahsolutions.kancil.KancilService"
    )
}
// BIND_AUTO_CREATE omitted — only connects if Kancil is already running
val available = bindService(intent, serviceConnection, 0)
```

If `onServiceConnected` fires, the service is up. Use this to show or hide AI features in your UI.

### Message protocol

| Constant | Value | Direction | Purpose |
|---|---|---|---|
| `MSG_DESCRIBE` | 1 | client → service | Run inference on a prompt + optional image |
| `MSG_RESULT` | 2 | service → client | Inference result |
| `MSG_ERROR` | 3 | service → client | Error string |
| `MSG_GET_STATUS` | 4 | client → service | Poll model readiness |
| `MSG_STATUS` | 5 | service → client | Readiness reply |

### Bundle keys

| Key | Type | Used in |
|---|---|---|
| `"prompt"` | `String` | `MSG_DESCRIBE` request |
| `"image"` | `ParcelFileDescriptor` | `MSG_DESCRIBE` request (optional) |
| `"result"` | `String` | `MSG_RESULT` reply |
| `"ready"` | `Boolean` | `MSG_STATUS` reply |
| `"error"` | `String` | `MSG_ERROR` reply |

### Example — describe an image

```kotlin
// Send request
val msg = Message.obtain(null, MSG_DESCRIBE)
msg.replyTo = myReplyMessenger
msg.data = Bundle().apply {
    putString("prompt", "Describe this image in detail.")
    putParcelable("image", ParcelFileDescriptor.open(imageFile, ParcelFileDescriptor.MODE_READ_ONLY))
}
kancilMessenger.send(msg)

// Receive reply
override fun handleMessage(msg: Message) {
    when (msg.what) {
        MSG_RESULT -> { val text = msg.data.getString("result") }
        MSG_ERROR  -> { val err  = msg.data.getString("error") }
    }
}
```

Images are passed as `ParcelFileDescriptor` so there is no Binder transaction size limit regardless of image size.

## Build

Requires JDK 17 (AGP 8.5 requires 17+):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug
```

llama.cpp is included as a git submodule — clone with:

```bash
git clone --recurse-submodules https://github.com/abdza/kancil.git
```

## Requirements

- Android 8.0+ (API 26)
- arm64-v8a or x86_64 device
- ~5 GB free storage for model files
- 4+ GB RAM recommended
