package com.example.llama

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufHeaderInfo(
    val version: Int,
    val tensorCount: Long,
    val metadataKvCount: Long,
    val fileSizeBytes: Long
)

sealed class GgufValidationResult {
    data class Valid(val header: GgufHeaderInfo) : GgufValidationResult()
    data class Invalid(val reason: String) : GgufValidationResult()
}

data class MemoryCheckResult(
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val fileSizeBytes: Long,
    val fileShareOfAvailableRam: Float,
    val isOverFortyPercentAvailableRam: Boolean,
    val isSystemLowMemory: Boolean
)

data class AbiSupportResult(
    val supportedAbis: List<String>,
    val supportsArmeabiV7a: Boolean
)

/**
 * Validates GGUF binary headers (magic "GGUF", version 2 or 3, non-zero tensor & KV counts),
 * checks 32-bit ARMv7 ABI compatibility, and inspects available RAM before loading.
 */
object GgufValidator {

    private const val MIN_GGUF_SIZE_BYTES = 1024L // At least 1 KB for header + minimal metadata
    private const val HEADER_BYTES_LEN = 24
    const val RAM_WARNING_RATIO = 0.40f

    fun checkDeviceAbi(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): AbiSupportResult {
        val list = supportedAbis.toList()
        val hasSupported = list.any {
            it.equals("armeabi-v7a", ignoreCase = true) ||
                it.equals("arm64-v8a", ignoreCase = true) ||
                it.equals("x86_64", ignoreCase = true)
        }
        return AbiSupportResult(
            supportedAbis = list,
            supportsArmeabiV7a = hasSupported
        )
    }

    fun inspectDeviceMemory(context: Context, modelFileSizeBytes: Long): MemoryCheckResult {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val avail = memInfo.availMem.coerceAtLeast(1L)
        val total = memInfo.totalMem.coerceAtLeast(avail)
        val ratio = modelFileSizeBytes.toFloat() / avail.toFloat()

        return MemoryCheckResult(
            availableRamBytes = avail,
            totalRamBytes = total,
            fileSizeBytes = modelFileSizeBytes,
            fileShareOfAvailableRam = ratio,
            isOverFortyPercentAvailableRam = ratio > RAM_WARNING_RATIO,
            isSystemLowMemory = memInfo.lowMemory
        )
    }

    fun validateFile(file: File): GgufValidationResult {
        if (!file.exists() || !file.isFile) {
            return GgufValidationResult.Invalid("Model file does not exist on disk.")
        }
        if (!file.name.endsWith(".gguf", ignoreCase = true)) {
            return GgufValidationResult.Invalid("Selected file '${file.name}' does not have a .gguf extension.")
        }
        val length = file.length()
        if (length < MIN_GGUF_SIZE_BYTES) {
            return GgufValidationResult.Invalid("File is too small (${length} bytes) to be a valid GGUF model.")
        }
        return try {
            FileInputStream(file).use { stream ->
                validateInputStream(stream, length)
            }
        } catch (e: Exception) {
            GgufValidationResult.Invalid("Failed to read GGUF file header: ${e.message ?: "I/O error"}")
        }
    }

    fun validateUri(
        context: Context,
        uri: Uri,
        displayName: String,
        reportedSizeBytes: Long
    ): GgufValidationResult {
        if (!displayName.endsWith(".gguf", ignoreCase = true)) {
            return GgufValidationResult.Invalid("Selected file '$displayName' is not a .gguf file.")
        }
        if (reportedSizeBytes in 1 until MIN_GGUF_SIZE_BYTES) {
            return GgufValidationResult.Invalid("File '$displayName' is truncated ($reportedSizeBytes bytes).")
        }
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return GgufValidationResult.Invalid("Unable to open selected file stream from system picker.")
            stream.use {
                validateInputStream(it, reportedSizeBytes)
            }
        } catch (e: Exception) {
            GgufValidationResult.Invalid("Could not inspect GGUF header: ${e.message ?: "I/O error"}")
        }
    }

    fun validateHeaderBytes(bytes: ByteArray, fileSizeBytes: Long): GgufValidationResult {
        if (bytes.size < HEADER_BYTES_LEN) {
            return GgufValidationResult.Invalid("Truncated GGUF header (read ${bytes.size} of $HEADER_BYTES_LEN bytes).")
        }

        // Magic bytes: 'G' (0x47), 'G' (0x47), 'U' (0x55), 'F' (0x46)
        if (bytes[0] != 0x47.toByte() ||
            bytes[1] != 0x47.toByte() ||
            bytes[2] != 0x55.toByte() ||
            bytes[3] != 0x46.toByte()
        ) {
            val hex = bytes.take(4).joinToString(" ") { String.format("0x%02X", it) }
            return GgufValidationResult.Invalid(
                "Invalid GGUF magic header ($hex). Expected 'GGUF' (0x47 0x47 0x55 0x46)."
            )
        }

        val buffer = ByteBuffer.wrap(bytes, 4, 20).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.int
        val tensorCount = buffer.long
        val metadataKvCount = buffer.long

        if (version != 2 && version != 3) {
            return GgufValidationResult.Invalid(
                "Unsupported GGUF format version $version. Only GGUF v2 and v3 are supported."
            )
        }

        if (tensorCount <= 0L || tensorCount > 100_000L) {
            return GgufValidationResult.Invalid(
                "Invalid GGUF tensor count ($tensorCount). The model file appears corrupt or empty."
            )
        }

        if (metadataKvCount <= 0L || metadataKvCount > 100_000L) {
            return GgufValidationResult.Invalid(
                "Invalid GGUF metadata KV count ($metadataKvCount). The model header is corrupt."
            )
        }

        return GgufValidationResult.Valid(
            GgufHeaderInfo(
                version = version,
                tensorCount = tensorCount,
                metadataKvCount = metadataKvCount,
                fileSizeBytes = fileSizeBytes
            )
        )
    }

    private fun validateInputStream(stream: InputStream, fileSizeBytes: Long): GgufValidationResult {
        val header = ByteArray(HEADER_BYTES_LEN)
        var offset = 0
        while (offset < HEADER_BYTES_LEN) {
            val read = stream.read(header, offset, HEADER_BYTES_LEN - offset)
            if (read == -1) break
            offset += read
        }
        return validateHeaderBytes(header.copyOf(offset), fileSizeBytes)
    }
}
