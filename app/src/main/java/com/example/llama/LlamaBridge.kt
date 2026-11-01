package com.example.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Callback interface invoked from C++ JNI (`Java_com_example_llama_LlamaBridge_nativeGenerateStream`)
 * as each token piece is decoded.
 */
interface TokenCallback {
    fun onToken(piece: String)
}

/**
 * Fixed low-end inference configuration matching the native C++ constants in `llama_jni.cpp`.
 */
object LowEndConfig {
    const val CONTEXT_SIZE = 256
    const val BATCH_SIZE = 64
    const val MAX_NEW_TOKENS = 128
    const val THREADS = 2
    const val USE_MMAP = true
    const val USE_MLOCK = false
    const val TEMPERATURE = 0.7f
    const val TOP_K = 40
    const val TOP_P = 0.9f
    const val REPEAT_PENALTY = 1.1f
    const val PINNED_LLAMA_COMMIT = "785322d17f2d51e5185cf996a1f4e46d87640841"
}

sealed class NativeLoadResult {
    data class Success(val modelDescription: String) : NativeLoadResult()
    data class Failure(
        val code: Int,
        val isOutOfMemory: Boolean,
        val message: String
    ) : NativeLoadResult()
}

sealed class NativeGenerationResult {
    data class Success(val tokensPerSecond: Float) : NativeGenerationResult()
    data class Failure(val isOutOfMemory: Boolean, val message: String) : NativeGenerationResult()
}

/**
 * JNI bridge to `libllama_jni.so` (`app/src/main/cpp/llama_jni.cpp`).
 * Never uses fake or simulated responses; all calls go directly to the native llama.cpp C API.
 */
class LlamaBridge {

    companion object {
        @Volatile
        var isNativeLibraryLoaded: Boolean = false
            private set

        @Volatile
        var nativeLinkError: String? = null
            private set

        init {
            tryLoadNativeLibrary()
        }

        @Synchronized
        fun tryLoadNativeLibrary(): Boolean {
            if (isNativeLibraryLoaded) return true
            return try {
                System.loadLibrary("llama_jni")
                isNativeLibraryLoaded = true
                nativeLinkError = null
                true
            } catch (e: UnsatisfiedLinkError) {
                isNativeLibraryLoaded = false
                nativeLinkError = e.message ?: "libllama_jni.so not found in APK"
                false
            } catch (t: Throwable) {
                isNativeLibraryLoaded = false
                nativeLinkError = t.message ?: "Unexpected error loading libllama_jni.so"
                false
            }
        }
    }

    // Exact JNI signatures verified against app/src/main/cpp/llama_jni.cpp
    external fun nativeInitBackend(): Boolean
    external fun nativeLoadModel(modelPath: String): Int
    external fun nativeGetModelDescription(): String
    external fun nativeGenerateStream(
        roles: Array<String>,
        contents: Array<String>,
        callback: TokenCallback
    ): Float
    external fun nativeStopGeneration()
    external fun nativeFreeModel()

    suspend fun loadModelFromFile(modelPath: String): NativeLoadResult = withContext(Dispatchers.IO) {
        if (!tryLoadNativeLibrary()) {
            return@withContext NativeLoadResult.Failure(
                code = -100,
                isOutOfMemory = false,
                message = "Native inference library (libllama_jni.so) is not loaded (${nativeLinkError ?: "missing JNI binary"})."
            )
        }

        try {
            val backendReady = nativeInitBackend()
            if (!backendReady) {
                return@withContext NativeLoadResult.Failure(
                    code = -99,
                    isOutOfMemory = false,
                    message = "llama_backend_init() failed to initialize native CPU backend."
                )
            }

            val status = nativeLoadModel(modelPath)
            when (status) {
                0 -> {
                    val desc = try {
                        nativeGetModelDescription()
                    } catch (_: Throwable) {
                        "GGUF model"
                    }
                    NativeLoadResult.Success(desc)
                }
                -3 -> NativeLoadResult.Failure(
                    code = status,
                    isOutOfMemory = false,
                    message = "llama_model_load_from_file() failed. The GGUF tensor layout may be corrupt, unsupported, or 32-bit virtual address space was exhausted."
                )
                -4 -> NativeLoadResult.Failure(
                    code = status,
                    isOutOfMemory = true,
                    message = "llama_init_from_model() failed to allocate KV cache (n_ctx=256, n_batch=64). Out of native memory."
                )
                else -> NativeLoadResult.Failure(
                    code = status,
                    isOutOfMemory = false,
                    message = "Native model load returned error code $status."
                )
            }
        } catch (oom: OutOfMemoryError) {
            NativeLoadResult.Failure(
                code = -101,
                isOutOfMemory = true,
                message = "Out of memory while loading model: ${oom.message ?: "JVM/Native heap exhausted"}"
            )
        } catch (ule: UnsatisfiedLinkError) {
            NativeLoadResult.Failure(
                code = -102,
                isOutOfMemory = false,
                message = "JNI symbol resolution failed: ${ule.message}"
            )
        } catch (t: Throwable) {
            NativeLoadResult.Failure(
                code = -103,
                isOutOfMemory = false,
                message = "Native exception while loading model: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    suspend fun generateReplyStream(
        roles: Array<String>,
        contents: Array<String>,
        onTokenPiece: (String) -> Unit
    ): NativeGenerationResult = withContext(Dispatchers.IO) {
        if (!tryLoadNativeLibrary()) {
            return@withContext NativeGenerationResult.Failure(
                isOutOfMemory = false,
                message = "Cannot run inference: native library libllama_jni.so is not loaded."
            )
        }

        try {
            val callback = object : TokenCallback {
                override fun onToken(piece: String) {
                    onTokenPiece(piece)
                }
            }
            val tokPerSec = nativeGenerateStream(roles, contents, callback)
            if (tokPerSec < 0f) {
                val reason = when (tokPerSec.toInt()) {
                    -1 -> "No model or context loaded in native engine."
                    -4 -> "llama_tokenize() failed on input prompt."
                    -5 -> "llama_decode() failed during prompt evaluation (possible out-of-memory or context overflow)."
                    else -> "Native generation failed with code ${tokPerSec.toInt()}."
                }
                NativeGenerationResult.Failure(
                    isOutOfMemory = (tokPerSec.toInt() == -5),
                    message = reason
                )
            } else {
                NativeGenerationResult.Success(tokPerSec)
            }
        } catch (oom: OutOfMemoryError) {
            NativeGenerationResult.Failure(
                isOutOfMemory = true,
                message = "Out of memory during token generation: ${oom.message ?: "heap exhausted"}"
            )
        } catch (t: Throwable) {
            NativeGenerationResult.Failure(
                isOutOfMemory = false,
                message = "Native inference error: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    fun requestStop() {
        if (!isNativeLibraryLoaded) return
        try {
            nativeStopGeneration()
        } catch (_: Throwable) {
            // Ignore if native library not initialized
        }
    }

    fun freeModel() {
        if (!isNativeLibraryLoaded) return
        try {
            nativeFreeModel()
        } catch (_: Throwable) {
            // Ignore during teardown
        }
    }
}
