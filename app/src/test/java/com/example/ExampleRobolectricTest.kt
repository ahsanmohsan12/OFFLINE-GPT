package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.llama.GgufValidationResult
import com.example.llama.GgufValidator
import com.example.ui.ChatMessage
import com.example.ui.ChatScreen
import com.example.ui.ChatUiState
import com.example.ui.ErrorBannerCategory
import com.example.ui.ErrorBannerState
import com.example.ui.theme.PocketGgufTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun ggufValidator_acceptsValidV3Header_andRejectsInvalidMagic() {
        val validBytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x47.toByte()) // 'G'
            put(0x47.toByte()) // 'G'
            put(0x55.toByte()) // 'U'
            put(0x46.toByte()) // 'F'
            putInt(3)          // version 3
            putLong(145L)      // tensor_count
            putLong(24L)       // metadata_kv_count
        }.array()

        val validResult = GgufValidator.validateHeaderBytes(validBytes, 105_000_000L)
        assertTrue(validResult is GgufValidationResult.Valid)

        val badBytes = validBytes.copyOf().apply { this[0] = 0x00 }
        val invalidResult = GgufValidator.validateHeaderBytes(badBytes, 105_000_000L)
        assertTrue(invalidResult is GgufValidationResult.Invalid)
    }

    @Test
    fun chatScreen_displaysErrorBannerAndStreamingTokensPerSec() {
        val testState = ChatUiState(
            modelName = "SmolLM2-135M-Instruct-Q4_K_M.gguf",
            modelSubtitle = "100 MB • ctx 256",
            isModelLoaded = true,
            isGenerating = false,
            errorBanner = ErrorBannerState(
                category = ErrorBannerCategory.LOW_RAM,
                message = "Model exceeds 40% of available RAM"
            ),
            messages = listOf(
                ChatMessage(
                    id = 1L,
                    isUser = true,
                    text = "Hello offline AI!",
                    timestamp = "10:15"
                ),
                ChatMessage(
                    id = 2L,
                    isUser = false,
                    text = "Hello! I am running locally via llama.cpp.",
                    timestamp = "10:15",
                    isStreaming = false,
                    tokensPerSec = 4.25f
                )
            )
        )

        composeTestRule.setContent {
            PocketGgufTheme {
                ChatScreen(
                    uiState = testState,
                    onPickModelClick = {},
                    onSendMessage = {},
                    onStopGeneration = {},
                    onClearChat = {},
                    onDismissError = {},
                    onConfirmLowRamLoad = {},
                    onCancelLowRamLoad = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello offline AI!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello! I am running locally via llama.cpp.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tokens_per_sec_label").assertIsDisplayed()
    }
}
