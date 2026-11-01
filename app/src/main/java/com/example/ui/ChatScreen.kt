package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.llama.LowEndConfig
import com.example.ui.theme.ErrorBannerRedBg
import com.example.ui.theme.ErrorBannerRedText
import com.example.ui.theme.WhatsAppAiBubbleDark
import com.example.ui.theme.WhatsAppAiBubbleLight
import com.example.ui.theme.WhatsAppGreenAccentDark
import com.example.ui.theme.WhatsAppGreenAccentLight
import com.example.ui.theme.WhatsAppUserBubbleDark
import com.example.ui.theme.WhatsAppUserBubbleLight
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onPickModelClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onClearChat: () -> Unit,
    onDismissError: () -> Unit,
    onConfirmLowRamLoad: () -> Unit,
    onCancelLowRamLoad: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var showEngineSpecDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val isDark = isSystemInDarkTheme()

    // Auto-scroll to bottom when messages or streaming tokens update
    val lastMessageTextLen = uiState.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(uiState.messages.size, lastMessageTextLen, uiState.isGenerating) {
        if (uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${uiState.modelName} • ${uiState.modelSubtitle}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("top_bar_model_name")
                        )
                    }
                },
                actions = {
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(
                            onClick = onClearChat,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.clear_chat_content_desc)
                            )
                        }
                    }
                    IconButton(
                        onClick = { showEngineSpecDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("engine_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.engine_info_content_desc)
                        )
                    }
                    IconButton(
                        onClick = onPickModelClick,
                        enabled = !uiState.isBusyLoadingModel,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("pick_model_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.pick_gguf_content_desc)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Red Error Banner for: bad GGUF file, model load failure, low RAM, out of memory, unsupported ABI
            uiState.errorBanner?.let { banner ->
                RedErrorBanner(
                    banner = banner,
                    onDismiss = onDismissError
                )
            }

            // Copy progress bar when copying .gguf once to app storage or loading via mmap
            if (uiState.isBusyLoadingModel) {
                ModelCopyOrLoadBar(uiState = uiState)
            }

            // Typing status bar when AI is streaming tokens
            if (uiState.isGenerating) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("typing_indicator_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isDark) WhatsAppGreenAccentDark else WhatsAppGreenAccentLight
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${uiState.modelName} is ${stringResource(R.string.typing_indicator)}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            // Chat message area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (uiState.messages.isEmpty()) {
                    EmptyChatPlaceholder(
                        uiState = uiState,
                        onPickModelClick = onPickModelClick,
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 600.dp)
                            .testTag("chat_message_list"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = uiState.messages,
                            key = { it.id }
                        ) { message ->
                            WhatsAppChatBubble(
                                message = message,
                                isDark = isDark
                            )
                        }
                    }
                }
            }

            // Bottom WhatsApp-style input row: rounded text box + round Send / Stop button
            BottomComposerBar(
                inputText = inputText,
                onInputTextChange = { inputText = it },
                isGenerating = uiState.isGenerating,
                isBusyLoading = uiState.isBusyLoadingModel,
                isDark = isDark,
                onSend = {
                    val toSend = inputText.trim()
                    if (toSend.isNotEmpty()) {
                        inputText = ""
                        onSendMessage(toSend)
                    }
                },
                onStop = onStopGeneration
            )
        }
    }

    // Before-loading warning dialog if GGUF file size > 40% of available RAM
    uiState.ramWarningDialog?.let { warning ->
        AlertDialog(
            onDismissRequest = onCancelLowRamLoad,
            modifier = Modifier.testTag("low_ram_warning_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Low RAM Warning",
                    tint = ErrorBannerRedBg
                )
            },
            title = {
                Text(
                    text = "Low RAM Warning (>40%)",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Model '${warning.fileName}' (${warning.fileSizeMb} MB) is " +
                        "${warning.percentOfAvailableRam}% of currently available RAM (${warning.availableRamMb} MB).\n\n" +
                        "Loading a GGUF file larger than 40% of free RAM on a 1–2 GB 32-bit device may trigger " +
                        "the Android Low Memory Killer (LMK) or fail KV cache allocation. Proceed anyway?"
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmLowRamLoad,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorBannerRedBg),
                    modifier = Modifier.testTag("confirm_low_ram_load_button")
                ) {
                    Text("Load Anyway", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onCancelLowRamLoad,
                    modifier = Modifier.testTag("cancel_low_ram_load_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Engine & Device Diagnostics Dialog
    if (showEngineSpecDialog) {
        AlertDialog(
            onDismissRequest = { showEngineSpecDialog = false },
            title = {
                Text(
                    text = "Low-End ARMv7 Engine Specs",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Fixed Low-End Settings (llama.cpp):",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• Context: ${LowEndConfig.CONTEXT_SIZE} tokens\n" +
                            "• Batch: ${LowEndConfig.BATCH_SIZE} (ubatch ${LowEndConfig.BATCH_SIZE})\n" +
                            "• Max new tokens: ${LowEndConfig.MAX_NEW_TOKENS}\n" +
                            "• Threads: ${LowEndConfig.THREADS} CPU threads\n" +
                            "• Memory: mmap=ON, mlock=OFF\n" +
                            "• Sampler: temp=${LowEndConfig.TEMPERATURE}, top_k=${LowEndConfig.TOP_K}, " +
                            "top_p=${LowEndConfig.TOP_P}, repeat_penalty=${LowEndConfig.REPEAT_PENALTY}\n" +
                            "• Target ABI: armeabi-v7a (NEON on, no -march=native)\n" +
                            "• Pinned commit: ${LowEndConfig.PINNED_LLAMA_COMMIT}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Device Telemetry:",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• Available RAM: ${uiState.availableRamMb} MB / ${uiState.totalRamMb} MB\n" +
                            "• 40% RAM Safe Limit: ${(uiState.availableRamMb * 0.40f).toInt()} MB\n" +
                            "• Device ABIs: ${uiState.deviceAbis}\n" +
                            "• JNI Library (libllama_jni.so): ${if (uiState.isNativeLibLoaded) "Loaded" else "Not compiled in preview container"}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showEngineSpecDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun RedErrorBanner(
    banner: ErrorBannerState,
    onDismiss: () -> Unit
) {
    Surface(
        color = ErrorBannerRedBg,
        contentColor = ErrorBannerRedText,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = banner.category.label,
                tint = ErrorBannerRedText,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = banner.category.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = ErrorBannerRedText
                    )
                )
                Text(
                    text = banner.message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = ErrorBannerRedText,
                        fontSize = 12.sp
                    )
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dismiss_error_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss_error_content_desc),
                    tint = ErrorBannerRedText
                )
            }
        }
    }
}

@Composable
private fun ModelCopyOrLoadBar(uiState: ChatUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("copy_progress_container")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            val progress = uiState.copyProgress
            if (progress != null && progress.totalBytes > 0L) {
                val copiedMb = progress.bytesCopied / (1024L * 1024L)
                val totalMb = progress.totalBytes / (1024L * 1024L)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Copying ${uiState.modelName} to app storage...",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$copiedMb / $totalMb MB (${progress.percent}%)",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .testTag("copy_progress_bar")
                )
            } else {
                Text(
                    text = uiState.modelSubtitle,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .testTag("copy_progress_bar")
                )
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(
    uiState: ChatUiState,
    onPickModelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (uiState.isModelLoaded) {
                    "Ready: ${uiState.modelName}"
                } else {
                    "Offline GGUF Chat (32-bit ARMv7)"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Recommended model: SmolLM2-135M-Instruct (Q4_K_M or Q8_0, ~100 MB).\n" +
                    "• Pick a .gguf file once — it copies to internal storage and auto-reloads on launch.\n" +
                    "• Fixed low-RAM profile: ctx=256, batch=64, max_new=128, threads=2, mmap=on, mlock=off.\n" +
                    "• Free RAM: ${uiState.availableRamMb} MB (40% safe limit: ${(uiState.availableRamMb * 0.40f).toInt()} MB).",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            )
            if (!uiState.isModelLoaded) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onPickModelClick,
                    enabled = !uiState.isBusyLoadingModel,
                    modifier = Modifier.testTag("empty_state_pick_model_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select .gguf Model File")
                }
            }
        }
    }
}

@Composable
private fun WhatsAppChatBubble(
    message: ChatMessage,
    isDark: Boolean
) {
    val bubbleColor = when {
        message.isUser && isDark -> WhatsAppUserBubbleDark
        message.isUser && !isDark -> WhatsAppUserBubbleLight
        !message.isUser && isDark -> WhatsAppAiBubbleDark
        else -> WhatsAppAiBubbleLight
    }

    val textColor = if (isDark) Color(0xFFE9EDEF) else Color(0xFF111B21)
    val metaColor = if (isDark) Color(0xFF8696A0) else Color(0xFF667781)

    val bubbleShape = if (message.isUser) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            shadowElevation = 0.8.dp,
            modifier = Modifier.widthIn(min = 72.dp, max = 310.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (message.isStreaming && message.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.typing_indicator),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = metaColor,
                            fontStyle = FontStyle.Italic
                        ),
                        modifier = Modifier.testTag("typing_indicator")
                    )
                } else {
                    val displayBody = if (message.isStreaming) {
                        "${message.text} ▍"
                    } else {
                        message.text
                    }
                    Text(
                        text = displayBody,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isStreaming && message.text.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.typing_indicator),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = metaColor,
                                fontStyle = FontStyle.Italic,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.testTag("typing_indicator")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = metaColor,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }

        // Small grey line under each AI reply with tokens/sec
        if (!message.isUser && !message.isStreaming && message.tokensPerSec != null) {
            Text(
                text = String.format(Locale.US, "%.2f tokens/sec", message.tokensPerSec),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = metaColor,
                    fontSize = 11.sp
                ),
                modifier = Modifier
                    .padding(start = 6.dp, top = 2.dp)
                    .testTag("tokens_per_sec_label")
            )
        }
    }
}

@Composable
private fun BottomComposerBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isGenerating: Boolean,
    isBusyLoading: Boolean,
    isDark: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rounded WhatsApp-style text field
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) WhatsAppAiBubbleDark else Color.White,
            shadowElevation = 1.dp,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = stringResource(R.string.message_placeholder),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp
                        )
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    enabled = !isBusyLoading,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!isGenerating) {
                                onSend()
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("message_input")
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Round Send button that becomes a Stop button while the AI is replying
        val actionBg = when {
            isGenerating -> ErrorBannerRedBg
            isDark -> WhatsAppGreenAccentDark
            else -> WhatsAppTealPrimaryLightColor
        }

        IconButton(
            onClick = {
                if (isGenerating) {
                    onStop()
                } else {
                    onSend()
                }
            },
            enabled = isGenerating || (!isBusyLoading && inputText.isNotBlank()),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(actionBg)
                .testTag("send_or_stop_button")
        ) {
            if (isGenerating) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.stop_generation_content_desc),
                    tint = Color.White
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message_content_desc),
                    tint = Color.White
                )
            }
        }
    }
}

private val WhatsAppTealPrimaryLightColor = Color(0xFF075E54)
