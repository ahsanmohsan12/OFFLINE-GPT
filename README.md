# PocketGGUF — Offline 32-Bit ARMv7 (`armeabi-v7a`) GGUF Chatbot

PocketGGUF is a native Android Studio project (**Kotlin + Jetpack Compose + C++ JNI + `llama.cpp`**) engineered specifically for low-end 32-bit ARMv7 phones (**1–2 GB RAM, Android 5.0 / API 21+**) running **`SmolLM2-135M-Instruct` (`Q4_K_M` or `Q8_0`, ~100 MB)** completely offline.

---

## 1. Integrated `llama.cpp` & Verified JNI / C API Signatures

- **Integrated Source Tree:** `app/src/main/cpp/llama.cpp` (also configured with CMake `FetchContent` fallback)
- **Pinned Commit Hash:** `785322d17f2d51e5185cf996a1f4e46d87640841`
- **Target ABI:** `armeabi-v7a` (`-mfpu=neon -mfloat-abi=softfp`, `-DGGML_NATIVE=OFF`)
- **Packaged Native Libraries in APK:**
  - `lib/armeabi-v7a/libllama_jni.so` (~4.09 MB, statically linking `llama`, `ggml`, `ggml-base`, `ggml-cpu`)
  - `lib/armeabi-v7a/libc++_shared.so`

Every C API call in `app/src/main/cpp/llama_jni.cpp` is verified against `app/src/main/cpp/llama.cpp/include/llama.h` and compiled with Android NDK `26.3.11579264` + CMake `3.22.1`:

| JNI Function (`LlamaBridge.kt`) | Native C++ Implementation (`llama_jni.cpp`) | `llama.h` C API Functions Used |
|---|---|---|
| `nativeInitBackend(): Boolean` | `Java_com_example_llama_LlamaBridge_nativeInitBackend` | `llama_backend_init()` |
| `nativeLoadModel(modelPath: String): Int` | `Java_com_example_llama_LlamaBridge_nativeLoadModel` | `llama_model_default_params()`, `llama_model_load_from_file()`, `llama_context_default_params()`, `llama_init_from_model()`, `llama_model_chat_template()`, `llama_n_ctx()` |
| `nativeGetModelDescription(): String` | `Java_com_example_llama_LlamaBridge_nativeGetModelDescription` | `llama_model_desc()` |
| `nativeGenerateStream(roles, contents, callback): Float` | `Java_com_example_llama_LlamaBridge_nativeGenerateStream` | `llama_model_get_vocab()`, `llama_chat_apply_template()`, `llama_get_memory()`, `llama_memory_clear()`, `llama_tokenize()`, `llama_sampler_chain_default_params()`, `llama_sampler_chain_init()`, `llama_sampler_chain_add()`, `llama_sampler_init_penalties()`, `llama_sampler_init_top_k()`, `llama_sampler_init_top_p()`, `llama_sampler_init_temp()`, `llama_sampler_init_dist()`, `llama_sampler_accept()`, `llama_sampler_sample()`, `llama_sampler_free()`, `llama_batch_init()`, `llama_decode()`, `llama_batch_free()`, `llama_vocab_is_eog()`, `llama_token_to_piece()` |
| `nativeStopGeneration()` | `Java_com_example_llama_LlamaBridge_nativeStopGeneration` | Sets `std::atomic<bool> g_stop_requested{true}` checked on every batch and token step |
| `nativeFreeModel()` | `Java_com_example_llama_LlamaBridge_nativeFreeModel` | `llama_free()`, `llama_model_free()`, `llama_backend_free()` |

---

## 2. Fixed Low-End Engine Defaults

Configured in `app/src/main/cpp/llama_jni.cpp` and `app/src/main/java/com/example/llama/LlamaBridge.kt`:

- **Context length (`n_ctx`):** `256`
- **Batch size (`n_batch` / `n_ubatch`):** `64`
- **Max new tokens:** `128`
- **CPU threads (`n_threads` / `n_threads_batch`):** `2`
- **Memory mapping (`load_mode`):** `LLAMA_LOAD_MODE_MMAP` (`mmap` on, `mlock` off)
- **Sampling:** `temp = 0.7f`, `top_k = 40`, `top_p = 0.9f`, `repeat_penalty = 1.1f` (`penalty_last_n = 64`)
- **Strict Load Verification:** `ChatViewModel` and `LlamaBridge.loadModelFromFile()` never report `isModelLoaded = true` unless `System.loadLibrary("llama_jni")`, `nativeInitBackend()`, and `nativeLoadModel()` (`status == 0`) all succeed.

---

## 3. Exact Build & Install Instructions in Android Studio

### Step 1: Ensure NDK & CMake Are Installed
`llama.cpp` is already integrated inside `app/src/main/cpp/llama.cpp` (with a CMake `FetchContent` fallback if that directory is removed).
In **Android Studio**:
1. Open **Settings / Preferences → Languages & Frameworks → Android SDK → SDK Tools**.
2. Ensure **NDK (Side by side)** (`26.3.11579264`) and **CMake** (`3.22.1`) are installed.

### Step 2: Build the APK
From the project root in Android Studio Terminal:

```bash
./gradlew :app:assembleDebug
```

Verify that `lib/armeabi-v7a/libllama_jni.so` is packaged inside the APK:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "lib/"
```

### Step 3: Install and Load `SmolLM2-135M-Instruct-Q4_K_M.gguf`
1. Install onto your 32-bit ARMv7 phone:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Copy `SmolLM2-135M-Instruct-Q4_K_M.gguf` (~105 MB) to the phone's `Download/` folder.
3. Open **PocketGGUF** and tap the **Folder** button in the top bar to pick the `.gguf` file.
   - The app validates the 24-byte GGUF binary header (`"GGUF"` magic, version 2/3, tensor count, metadata KV count).
   - It warns if the file size exceeds **40% of available RAM** (`ActivityManager.MemoryInfo.availMem`).
   - It copies the model **once** to `files/models/` with a progress bar and auto-reloads it on subsequent launches.

---

## 4. Limitations on 1–2 GB RAM 32-Bit Phones
1. **32-Bit Virtual Address Space (~2–3 GB) & Free RAM:** Even with `mmap` enabled (`LLAMA_LOAD_MODE_MMAP`), 32-bit processes have a 2–3 GB virtual address limit and 1–2 GB phones typically have 250–600 MB of free physical RAM. Models larger than ~250 MB risk heavy page thrashing or termination by the Android Low Memory Killer (LMK).
2. **256-Token Context Window (`n_ctx = 256`, `max_new_tokens = 128`):** To keep the KV cache small and responsive on 2 CPU threads, only recent chat turns are kept in the prompt window.
3. **Generation Speed (~1.5–5 tokens/sec):** On low-end 32-bit ARMv7 cores (e.g., Cortex-A7 / Cortex-A53) with 2 threads and NEON SIMD, expect ~1.5 to 5.0 tokens/sec on `SmolLM2-135M-Instruct-Q4_K_M.gguf`.
4. **64-Bit-Only Devices (e.g., Pixel 7/8/9):** Because the APK packages `lib/armeabi-v7a/libllama_jni.so` for 32-bit ARMv7 devices as specified, newer 64-bit-only hardware that removed 32-bit Zygote support (`arm64-v8a` only) will display the red **Unsupported Device ABI** banner unless `"arm64-v8a"` is added to `ndk.abiFilters` in `app/build.gradle.kts`.
