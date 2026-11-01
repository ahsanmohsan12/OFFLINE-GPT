package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.ChatScreen
import com.example.ui.ChatViewModel
import com.example.ui.theme.PocketGgufTheme

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PocketGgufTheme {
                val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

                // System file picker for .gguf files
                val openDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {
                            // Some providers do not support persistable permissions; safe because we copy once
                        }
                        chatViewModel.onModelUriPicked(uri)
                    }
                }

                ChatScreen(
                    uiState = uiState,
                    onPickModelClick = {
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onSendMessage = { text ->
                        chatViewModel.sendMessage(text)
                    },
                    onStopGeneration = {
                        chatViewModel.stopGeneration()
                    },
                    onClearChat = {
                        chatViewModel.clearChatHistory()
                    },
                    onDismissError = {
                        chatViewModel.dismissErrorBanner()
                    },
                    onConfirmLowRamLoad = {
                        chatViewModel.confirmLoadDespiteLowRamWarning()
                    },
                    onCancelLowRamLoad = {
                        chatViewModel.dismissLowRamWarningDialog()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        // Explicitly free native llama.cpp model and context when activity is destroyed (not rotating)
        if (isFinishing) {
            chatViewModel.releaseNativeResources()
        }
        super.onDestroy()
    }
}
