#!/usr/bin/env bash
# Run this once after cloning to vendor llama.cpp.
set -euo pipefail

LLAMA_DIR="app/src/main/cpp/llama.cpp"

if [ ! -d "$LLAMA_DIR" ]; then
    echo "Cloning llama.cpp …"
    git clone --depth 1 https://github.com/ggerganov/llama.cpp "$LLAMA_DIR"
else
    echo "llama.cpp already present at $LLAMA_DIR"
fi

echo ""
echo "Done! Open the project in Android Studio and hit Run."
echo "The app will download the Gemma 4 1B-Instruct GGUF (~700 MB) on first launch."
