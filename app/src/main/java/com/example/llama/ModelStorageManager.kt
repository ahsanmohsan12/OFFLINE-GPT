package com.example.llama

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class PickedFileMetadata(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long
)

data class CopyProgress(
    val bytesCopied: Long,
    val totalBytes: Long,
    val percent: Int
)

/**
 * Manages copying the selected .gguf model ONCE into app-private storage (`filesDir/models/`)
 * with streaming progress updates, and remembering the saved path for automatic reload on launch.
 */
class ModelStorageManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "pocket_gguf_storage_prefs"
        private const val KEY_SAVED_MODEL_PATH = "saved_model_path"
        private const val KEY_SAVED_MODEL_NAME = "saved_model_name"
        private const val MODELS_SUBDIR = "models"
        private const val COPY_BUFFER_BYTES = 64 * 1024 // 64 KB buffer for low-RAM efficiency
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getModelsDir(): File {
        val dir = File(context.filesDir, MODELS_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getSavedModelFile(): File? {
        val path = prefs.getString(KEY_SAVED_MODEL_PATH, null) ?: return null
        val file = File(path)
        return if (file.exists() && file.isFile && file.length() > 0L) {
            file
        } else {
            clearSavedModel()
            null
        }
    }

    fun getSavedModelDisplayName(): String? {
        return prefs.getString(KEY_SAVED_MODEL_NAME, null)
    }

    fun saveActiveModel(file: File, displayName: String) {
        prefs.edit()
            .putString(KEY_SAVED_MODEL_PATH, file.absolutePath)
            .putString(KEY_SAVED_MODEL_NAME, displayName)
            .apply()
    }

    fun clearSavedModel() {
        prefs.edit()
            .remove(KEY_SAVED_MODEL_PATH)
            .remove(KEY_SAVED_MODEL_NAME)
            .apply()
    }

    fun resolvePickedFileMetadata(uri: Uri): PickedFileMetadata {
        var name = "model.gguf"
        var size = -1L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    name = cursor.getString(nameIdx) ?: name
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }

        if (size <= 0L) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) {
                        size = afd.length
                    }
                }
            } catch (_: Exception) {
                // Ignore if provider does not report descriptor length
            }
        }

        return PickedFileMetadata(
            uri = uri,
            displayName = sanitizeFileName(name),
            sizeBytes = size
        )
    }

    /**
     * Copies the picked GGUF URI to `filesDir/models/<displayName>` if not already present with the
     * exact same byte size. Deletes previous models in `filesDir/models/` first so low-end phones
     * do not waste internal flash storage.
     */
    suspend fun copyModelToAppStorageOnce(
        metadata: PickedFileMetadata,
        onProgress: (CopyProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelsDir = getModelsDir()
            val targetFile = File(modelsDir, metadata.displayName)

            // If already copied with identical file size and valid GGUF header, reuse without copying again
            if (targetFile.exists() &&
                metadata.sizeBytes > 0L &&
                targetFile.length() == metadata.sizeBytes &&
                GgufValidator.validateFile(targetFile) is GgufValidationResult.Valid
            ) {
                onProgress(
                    CopyProgress(
                        bytesCopied = targetFile.length(),
                        totalBytes = targetFile.length(),
                        percent = 100
                    )
                )
                saveActiveModel(targetFile, metadata.displayName)
                return@withContext Result.success(targetFile)
            }

            // Remove old cached GGUF files to conserve storage on low-end devices
            modelsDir.listFiles()?.forEach { existing ->
                if (existing.absolutePath != targetFile.absolutePath) {
                    existing.delete()
                }
            }

            val tempFile = File(modelsDir, "${metadata.displayName}.part")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val inputStream = context.contentResolver.openInputStream(metadata.uri)
                ?: return@withContext Result.failure(
                    IOException("Could not open input stream for ${metadata.displayName}")
                )

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    val total = metadata.sizeBytes.coerceAtLeast(0L)
                    var lastReportedPercent = -1

                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read

                        val percent = if (total > 0L) {
                            ((copied * 100L) / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (percent != lastReportedPercent || total == 0L) {
                            lastReportedPercent = percent
                            onProgress(
                                CopyProgress(
                                    bytesCopied = copied,
                                    totalBytes = total,
                                    percent = percent
                                )
                            )
                        }
                    }
                    output.fd.sync()
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            saveActiveModel(targetFile, metadata.displayName)
            Result.success(targetFile)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IOException("Out of memory while copying model file.", oom))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
        return when {
            cleaned.isEmpty() -> "model.gguf"
            !cleaned.endsWith(".gguf", ignoreCase = true) -> "$cleaned.gguf"
            else -> cleaned
        }
    }
}
