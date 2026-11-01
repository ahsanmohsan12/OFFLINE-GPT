package com.example.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llama.CopyProgress
import com.example.llama.GgufHeaderInfo
import com.example.llama.GgufValidationResult
import com.example.llama.GgufValidator
import com.example.llama.LlamaBridge
import com.example.llama.ModelStorageManager
import com.example.llama.NativeGenerationResult
import com.example.llama.NativeLoadResult
import com.example.llama.PickedFileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class ErrorBannerCategory(val label: String) {
    BAD_GGUF("Bad GGUF File"),
    MODEL_LOAD_FAILURE("Model Load Failure"),
    LOW_RAM("Low RAM Warning"),
    OUT_OF_MEMORY("Out of Memory"),
    UNSUPPORTED_ABI("Unsupported Device ABI")
}

data class ErrorBannerState(
    val category: ErrorBannerCategory,
    val message: String
)

data class RamWarningDialogState(
    val fileName: String,
    val fileSizeMb: Int,
    val availableRamMb: Int,
    val percentOfAvailableRam: Int,
    val pendingUriMetadata: PickedFileMetadata? = null,
    val pendingSavedFile: File? = null
)

data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val timestamp: String,
    val isStreaming: Boolean = false,
    val tokensPerSec: Float? = null
)

data class ChatUiState(
    val modelName: String = "No model loaded",
    val modelSubtitle: String = "Tap folder icon to pick a .gguf model",
    val isModelLoaded: Boolean = false,
    val isBusyLoadingModel: Boolean = false,
    val copyProgress: CopyProgress? = null,
    val isGenerating: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val errorBanner: ErrorBannerState? = null,
    val ramWarningDialog: RamWarningDialogState? = null,
    val availableRamMb: Int = 0,
    val totalRamMb: Int = 0,
    val deviceAbis: String = Build.SUPPORTED_ABIS.joinToString(", "),
    val isArmV7Supported: Boolean = true,
    val isNativeLibLoaded: Boolean = LlamaBridge.isNativeLibraryLoaded,
    val ggufHeaderInfo: GgufHeaderInfo? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val storageManager = ModelStorageManager(appContext)
    private val llamaBridge = LlamaBridge()
    private val idCounter = AtomicLong(1L)

    private var generationJob: Job? = null
    private var modelLoadJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        initializeAndAutoReload()
    }

    private fun initializeAndAutoReload() {
        val abiCheck = GgufValidator.checkDeviceAbi()
        val memCheck = GgufValidator.inspectDeviceMemory(appContext, 0L)
        val availMb = (memCheck.availableRamBytes / (1024L * 1024L)).toInt()
        val totalMb = (memCheck.totalRamBytes / (1024L * 1024L)).toInt()

        var initialBanner: ErrorBannerState? = null
        if (!abiCheck.supportsArmeabiV7a) {
            initialBanner = ErrorBannerState(
                category = ErrorBannerCategory.UNSUPPORTED_ABI,
                message = "This app targets 32-bit armeabi-v7a (ARMv7 NEON), but this device reports ABIs: " +
                    "${abiCheck.supportedAbis.joinToString(", ")}. Native inference cannot run on this CPU."
            )
        } else if (memCheck.isSystemLowMemory) {
            initialBanner = ErrorBannerState(
                category = ErrorBannerCategory.LOW_RAM,
                message = "Android reports low system memory ($availMb MB free of $totalMb MB). Close background apps before loading a GGUF model."
            )
        }

        _uiState.update {
            it.copy(
                availableRamMb = availMb,
                totalRamMb = totalMb,
                deviceAbis = abiCheck.supportedAbis.joinToString(", "),
                isArmV7Supported = abiCheck.supportsArmeabiV7a,
                isNativeLibLoaded = LlamaBridge.isNativeLibraryLoaded,
                errorBanner = initialBanner
            )
        }

        // Check if a previously copied model exists in app storage for auto-reload
        val savedFile = storageManager.getSavedModelFile()
        val savedName = storageManager.getSavedModelDisplayName() ?: savedFile?.name
        if (savedFile != null && savedName != null) {
            val validation = GgufValidator.validateFile(savedFile)
            when (validation) {
                is GgufValidationResult.Invalid -> {
                    storageManager.clearSavedModel()
                    _uiState.update {
                        it.copy(
                            errorBanner = ErrorBannerState(
                                category = ErrorBannerCategory.BAD_GGUF,
                                message = "Saved model '$savedName' failed GGUF validation: ${validation.reason}"
                            )
                        )
                    }
                }
                is GgufValidationResult.Valid -> {
                    val ramStatus = GgufValidator.inspectDeviceMemory(appContext, savedFile.length())
                    val fileMb = (savedFile.length() / (1024L * 1024L)).toInt()
                    val freeMb = (ramStatus.availableRamBytes / (1024L * 1024L)).toInt()
                    val pct = (ramStatus.fileShareOfAvailableRam * 100f).toInt()

                    if (ramStatus.isOverFortyPercentAvailableRam) {
                        _uiState.update {
                            it.copy(
                                modelName = savedName,
                                modelSubtitle = "Paused auto-load: model is $pct% of free RAM",
                                ggufHeaderInfo = validation.header,
                                errorBanner = ErrorBannerState(
                                    category = ErrorBannerCategory.LOW_RAM,
                                    message = "Model '$savedName' ($fileMb MB) is $pct% of available RAM ($freeMb MB), exceeding the 40% safety threshold."
                                ),
                                ramWarningDialog = RamWarningDialogState(
                                    fileName = savedName,
                                    fileSizeMb = fileMb,
                                    availableRamMb = freeMb,
                                    percentOfAvailableRam = pct,
                                    pendingSavedFile = savedFile
                                )
                            )
                        }
                    } else {
                        loadValidatedFileFromStorage(savedFile, savedName, validation.header)
                    }
                }
            }
        }
    }

    fun onModelUriPicked(uri: Uri?) {
        if (uri == null) return

        viewModelScope.launch(Dispatchers.IO) {
            val abiCheck = GgufValidator.checkDeviceAbi()
            if (!abiCheck.supportsArmeabiV7a) {
                _uiState.update {
                    it.copy(
                        errorBanner = ErrorBannerState(
                            category = ErrorBannerCategory.UNSUPPORTED_ABI,
                            message = "Unsupported device ABI (${abiCheck.supportedAbis.joinToString()}). Only armeabi-v7a is supported."
                        )
                    )
                }
                return@launch
            }

            val metadata = storageManager.resolvePickedFileMetadata(uri)
            val validation = GgufValidator.validateUri(
                context = appContext,
                uri = uri,
                displayName = metadata.displayName,
                reportedSizeBytes = metadata.sizeBytes
            )

            when (validation) {
                is GgufValidationResult.Invalid -> {
                    _uiState.update {
                        it.copy(
                            errorBanner = ErrorBannerState(
                                category = ErrorBannerCategory.BAD_GGUF,
                                message = validation.reason
                            )
                        )
                    }
                }
                is GgufValidationResult.Valid -> {
                    val effectiveSize = if (metadata.sizeBytes > 0L) metadata.sizeBytes else validation.header.fileSizeBytes
                    val ramCheck = GgufValidator.inspectDeviceMemory(appContext, effectiveSize)
                    val fileMb = (effectiveSize / (1024L * 1024L)).toInt()
                    val freeMb = (ramCheck.availableRamBytes / (1024L * 1024L)).toInt()
                    val pct = (ramCheck.fileShareOfAvailableRam * 100f).toInt()

                    _uiState.update {
                        it.copy(
                            availableRamMb = freeMb,
                            totalRamMb = (ramCheck.totalRamBytes / (1024L * 1024L)).toInt(),
                            ggufHeaderInfo = validation.header
                        )
                    }

                    if (ramCheck.isOverFortyPercentAvailableRam) {
                        _uiState.update {
                            it.copy(
                                errorBanner = ErrorBannerState(
                                    category = ErrorBannerCategory.LOW_RAM,
                                    message = "Model '${metadata.displayName}' ($fileMb MB) uses $pct% of available RAM ($freeMb MB), over the 40% threshold."
                                ),
                                ramWarningDialog = RamWarningDialogState(
                                    fileName = metadata.displayName,
                                    fileSizeMb = fileMb,
                                    availableRamMb = freeMb,
                                    percentOfAvailableRam = pct,
                                    pendingUriMetadata = metadata
                                )
                            )
                        }
                    } else {
                        copyAndLoadPickedModel(metadata, validation.header)
                    }
                }
            }
        }
    }

    fun confirmLoadDespiteLowRamWarning() {
        val warning = _uiState.value.ramWarningDialog ?: return
        _uiState.update { it.copy(ramWarningDialog = null) }

        val pendingUriMeta = warning.pendingUriMetadata
        val pendingFile = warning.pendingSavedFile
        val header = _uiState.value.ggufHeaderInfo

        if (pendingUriMeta != null && header != null) {
            copyAndLoadPickedModel(pendingUriMeta, header)
        } else if (pendingFile != null) {
            val validation = GgufValidator.validateFile(pendingFile)
            if (validation is GgufValidationResult.Valid) {
                loadValidatedFileFromStorage(pendingFile, warning.fileName, validation.header)
            }
        }
    }

    fun dismissLowRamWarningDialog() {
        _uiState.update {
            it.copy(
                ramWarningDialog = null,
                modelSubtitle = if (it.isModelLoaded) it.modelSubtitle else "Load cancelled due to low RAM warning"
            )
        }
    }

    fun dismissErrorBanner() {
        _uiState.update { it.copy(errorBanner = null) }
    }

    private fun copyAndLoadPickedModel(metadata: PickedFileMetadata, header: GgufHeaderInfo) {
        modelLoadJob?.cancel()
        modelLoadJob = viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.isGenerating) {
                stopGeneration()
            }

            _uiState.update {
                it.copy(
                    isBusyLoadingModel = true,
                    modelName = metadata.displayName,
                    modelSubtitle = "Copying to app storage...",
                    copyProgress = CopyProgress(0L, metadata.sizeBytes.coerceAtLeast(0L), 0)
                )
            }

            val copyResult = storageManager.copyModelToAppStorageOnce(metadata) { progress ->
                _uiState.update { state ->
                    state.copy(
                        copyProgress = progress,
                        modelSubtitle = "Copying to app storage (${progress.percent}%)..."
                    )
                }
            }

            copyResult.fold(
                onSuccess = { copiedFile ->
                    _uiState.update { it.copy(copyProgress = null) }
                    val fileValidation = GgufValidator.validateFile(copiedFile)
                    when (fileValidation) {
                        is GgufValidationResult.Invalid -> {
                            copiedFile.delete()
                            storageManager.clearSavedModel()
                            _uiState.update {
                                it.copy(
                                    isBusyLoadingModel = false,
                                    isModelLoaded = false,
                                    modelSubtitle = "Invalid GGUF file",
                                    errorBanner = ErrorBannerState(
                                        category = ErrorBannerCategory.BAD_GGUF,
                                        message = fileValidation.reason
                                    )
                                )
                            }
                        }
                        is GgufValidationResult.Valid -> {
                            loadValidatedFileFromStorage(copiedFile, metadata.displayName, fileValidation.header)
                        }
                    }
                },
                onFailure = { err ->
                    val isOom = err is OutOfMemoryError || err.cause is OutOfMemoryError
                    _uiState.update {
                        it.copy(
                            isBusyLoadingModel = false,
                            copyProgress = null,
                            modelSubtitle = "Failed to copy model",
                            errorBanner = ErrorBannerState(
                                category = if (isOom) ErrorBannerCategory.OUT_OF_MEMORY else ErrorBannerCategory.MODEL_LOAD_FAILURE,
                                message = "Failed to copy '${metadata.displayName}' to app storage: ${err.message ?: "I/O failure"}"
                            )
                        )
                    }
                }
            )
        }
    }

    private fun loadValidatedFileFromStorage(file: File, displayName: String, header: GgufHeaderInfo) {
        modelLoadJob?.cancel()
        modelLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val fileSizeMb = (file.length() / (1024L * 1024L)).toInt()
            _uiState.update {
                it.copy(
                    isBusyLoadingModel = true,
                    copyProgress = null,
                    modelName = displayName,
                    modelSubtitle = "Loading GGUF via mmap (${fileSizeMb} MB)...",
                    ggufHeaderInfo = header
                )
            }

            when (val result = llamaBridge.loadModelFromFile(file.absolutePath)) {
                is NativeLoadResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusyLoadingModel = false,
                            isModelLoaded = true,
                            modelName = displayName,
                            modelSubtitle = "${result.modelDescription} • ${fileSizeMb} MB • ctx 256",
                            errorBanner = null
                        )
                    }
                }
                is NativeLoadResult.Failure -> {
                    val category = if (result.isOutOfMemory) {
                        ErrorBannerCategory.OUT_OF_MEMORY
                    } else {
                        ErrorBannerCategory.MODEL_LOAD_FAILURE
                    }
                    _uiState.update {
                        it.copy(
                            isBusyLoadingModel = false,
                            isModelLoaded = false,
                            modelName = displayName,
                            modelSubtitle = "GGUF verified ($fileSizeMb MB, v${header.version}, ${header.tensorCount} tensors) • Not loaded",
                            errorBanner = ErrorBannerState(
                                category = category,
                                message = result.message
                            )
                        )
                    }
                }
            }
        }
    }

    fun sendMessage(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || _uiState.value.isGenerating) return

        val state = _uiState.value
        if (!state.isModelLoaded) {
            _uiState.update {
                it.copy(
                    errorBanner = ErrorBannerState(
                        category = ErrorBannerCategory.MODEL_LOAD_FAILURE,
                        message = if (!LlamaBridge.isNativeLibraryLoaded) {
                            "Native inference library (libllama_jni.so) is not loaded. Build with NDK + CMake in Android Studio and load a .gguf model first."
                        } else {
                            "No GGUF model is loaded. Tap the folder button in the top bar to pick a .gguf model (e.g. SmolLM2-135M-Instruct)."
                        }
                    )
                )
            }
            return
        }

        val nowStr = formatTimestamp()
        val userMsg = ChatMessage(
            id = idCounter.getAndIncrement(),
            isUser = true,
            text = trimmed,
            timestamp = nowStr
        )
        val assistantMsgId = idCounter.getAndIncrement()
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            timestamp = nowStr,
            isStreaming = true,
            tokensPerSec = null
        )

        val updatedHistory = state.messages + userMsg
        _uiState.update {
            it.copy(
                messages = updatedHistory + assistantPlaceholder,
                isGenerating = true,
                errorBanner = null
            )
        }

        // Keep only the last few turns so we stay comfortably inside the 256-token context window
        val recentTurns = updatedHistory.takeLast(4)
        val rolesList = mutableListOf("system")
        val contentsList = mutableListOf("You are a helpful, concise offline assistant.")
        for (msg in recentTurns) {
            rolesList.add(if (msg.isUser) "user" else "assistant")
            contentsList.add(msg.text)
        }

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()

            val genResult = llamaBridge.generateReplyStream(
                roles = rolesList.toTypedArray(),
                contents = contentsList.toTypedArray(),
                onTokenPiece = { piece ->
                    sb.append(piece)
                    val currentText = sb.toString()
                    _uiState.update { current ->
                        current.copy(
                            messages = current.messages.map { m ->
                                if (m.id == assistantMsgId) {
                                    m.copy(text = currentText, isStreaming = true)
                                } else {
                                    m
                                }
                            }
                        )
                    }
                }
            )

            when (genResult) {
                is NativeGenerationResult.Success -> {
                    val finalText = sb.toString().trim().ifEmpty { "[Stopped before token output]" }
                    _uiState.update { current ->
                        current.copy(
                            isGenerating = false,
                            messages = current.messages.map { m ->
                                if (m.id == assistantMsgId) {
                                    m.copy(
                                        text = finalText,
                                        isStreaming = false,
                                        tokensPerSec = genResult.tokensPerSecond
                                    )
                                } else {
                                    m
                                }
                            }
                        )
                    }
                }
                is NativeGenerationResult.Failure -> {
                    val category = if (genResult.isOutOfMemory) {
                        ErrorBannerCategory.OUT_OF_MEMORY
                    } else {
                        ErrorBannerCategory.MODEL_LOAD_FAILURE
                    }
                    _uiState.update { current ->
                        current.copy(
                            isGenerating = false,
                            errorBanner = ErrorBannerState(
                                category = category,
                                message = genResult.message
                            ),
                            messages = current.messages.mapNotNull { m ->
                                if (m.id == assistantMsgId) {
                                    if (m.text.isBlank()) null else m.copy(isStreaming = false)
                                } else {
                                    m
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        llamaBridge.requestStop()
    }

    fun clearChatHistory() {
        if (_uiState.value.isGenerating) {
            stopGeneration()
        }
        _uiState.update { it.copy(messages = emptyList()) }
    }

    fun releaseNativeResources() {
        generationJob?.cancel()
        modelLoadJob?.cancel()
        llamaBridge.requestStop()
        llamaBridge.freeModel()
    }

    override fun onCleared() {
        releaseNativeResources()
        super.onCleared()
    }

    private fun formatTimestamp(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date())
    }
}
