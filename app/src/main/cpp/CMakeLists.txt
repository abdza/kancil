cmake_minimum_required(VERSION 3.22.1)
project("qwenchat")

set(LLAMA_DIR  ${CMAKE_SOURCE_DIR}/llama.cpp)
set(MTMD_DIR   ${LLAMA_DIR}/tools/mtmd)

# ── llama.cpp build options ──────────────────────────────────────────────────
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# Use the baseline arm64 arch so llama.cpp's own runtime CPU feature detection
# picks the right SIMD paths. Forcing armv8.4-a here generates dotprod/i8mm
# instructions that cause SIGILL on Snapdragon 845/855 and similar chips.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3 -DNDEBUG)
endif()

add_subdirectory(${LLAMA_DIR} llama_build)

# ── JNI bridge + mtmd sources compiled directly ───────────────────────────────
add_library(llama-android SHARED
    llama_bridge.cpp
    # mtmd core
    ${MTMD_DIR}/mtmd.cpp
    ${MTMD_DIR}/mtmd-audio.cpp
    ${MTMD_DIR}/mtmd-image.cpp
    ${MTMD_DIR}/mtmd-helper.cpp
    ${MTMD_DIR}/clip.cpp
    # vision model backends
    ${MTMD_DIR}/models/cogvlm.cpp
    ${MTMD_DIR}/models/conformer.cpp
    ${MTMD_DIR}/models/gemma4v.cpp
    ${MTMD_DIR}/models/glm4v.cpp
    ${MTMD_DIR}/models/hunyuanocr.cpp
    ${MTMD_DIR}/models/internvl.cpp
    ${MTMD_DIR}/models/kimivl.cpp
    ${MTMD_DIR}/models/kimik25.cpp
    ${MTMD_DIR}/models/nemotron-v2-vl.cpp
    ${MTMD_DIR}/models/llama4.cpp
    ${MTMD_DIR}/models/llava.cpp
    ${MTMD_DIR}/models/minicpmv.cpp
    ${MTMD_DIR}/models/paddleocr.cpp
    ${MTMD_DIR}/models/pixtral.cpp
    ${MTMD_DIR}/models/qwen2vl.cpp
    ${MTMD_DIR}/models/qwen3vl.cpp
    ${MTMD_DIR}/models/siglip.cpp
    ${MTMD_DIR}/models/whisper-enc.cpp
    ${MTMD_DIR}/models/deepseekocr.cpp
    ${MTMD_DIR}/models/mobilenetv5.cpp
    ${MTMD_DIR}/models/youtuvl.cpp
)

target_include_directories(llama-android PRIVATE
    ${LLAMA_DIR}/include          # llama.h
    ${LLAMA_DIR}/ggml/include     # ggml.h, ggml-alloc.h, ggml-backend.h, gguf.h
    ${LLAMA_DIR}/ggml/src         # ggml-cpp.h (private, needed by clip.cpp)
    ${LLAMA_DIR}/src              # llama-impl.h and other private llama headers
    ${LLAMA_DIR}                  # root (../../ from mtmd's perspective)
    ${MTMD_DIR}                   # mtmd.h, clip.h, etc.
    ${LLAMA_DIR}/vendor           # stb/stb_image.h, miniaudio/miniaudio.h
)

target_link_libraries(llama-android
    llama
    ggml
    android
    log
)

target_compile_features(llama-android PRIVATE cxx_std_17)
target_compile_options(llama-android PRIVATE -O3 -DNDEBUG -Wno-cast-qual)
