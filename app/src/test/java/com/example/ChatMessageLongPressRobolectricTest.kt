package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.media.VoicePlaybackState
import com.example.media.VoiceRecordingState
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.theme.ChatsoTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatMessageLongPressRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun longPressTextAndStickerMessages_opensActionDialogWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stickerFile = File(context.cacheDir, "ui_test_sticker.webp").apply {
            writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4))
        }
        var favoriteClicked = false
        val messages = listOf(
            MessageEntity(
                messageId = "msg_text_1",
                peerId = "peer_1",
                isOutgoing = true,
                type = MessageType.TEXT.name,
                textContent = "Hello long press",
                status = DeliveryStatus.SENT.name
            ),
            MessageEntity(
                messageId = "msg_sticker_1",
                peerId = "peer_1",
                isOutgoing = false,
                type = MessageType.STICKER.name,
                mediaLocalPath = stickerFile.absolutePath,
                fileName = "ui_test_sticker.webp",
                mimeType = "image/webp",
                transferProgress = 100,
                status = DeliveryStatus.READ.name
            )
        )
        val nowClock = MutableStateFlow(System.currentTimeMillis())

        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_1",
                        username = "Peer",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP"
                    ),
                    messages = messages,
                    currentMessageLimit = 150,
                    presence = PeerPresence.ONLINE,
                    isPeerTyping = false,
                    activeTransfers = emptyList(),
                    favoriteStickers = emptyList(),
                    nowClockFlow = nowClock,
                    voiceRecordingState = VoiceRecordingState(),
                    voicePlaybackState = VoicePlaybackState(),
                    showBackButton = true,
                    onBack = {},
                    onLoadMoreMessages = {},
                    onSendText = { _, _ -> },
                    onToggleReaction = { _, _ -> },
                    onTypingChanged = {},
                    onSendPhotoUri = { _, _ -> },
                    onSendVideoUri = { _, _ -> },
                    onSendDocumentUri = { _, _ -> },
                    onSendStickerStream = { _, _, _, _ -> },
                    onCheckStickerFavorited = { false },
                    onToggleFavoriteSticker = { favoriteClicked = true },
                    onRemoveFavoriteSticker = {},
                    onSendFavoriteSticker = { _, _ -> },
                    onStartVoiceRecording = {},
                    onCancelVoiceRecording = {},
                    onSendVoiceRecording = {},
                    onToggleVoicePlayback = { _, _ -> },
                    onOpenAttachment = { _, _ -> },
                    onCancelTransfer = {},
                    onRetryDownload = {},
                    onSetSelfDestruct = {},
                    onVerifyPeerKey = {},
                    onReconnect = {},
                    onDeleteMessage = {},
                    onDeleteConversation = {},
                    onNotify = {}
                )
            }
        }

        // Long press text message -> opens dialog -> tap reply closes cleanly
        composeTestRule.onNodeWithTag("message_bubble_msg_text_1")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("message_action_reply").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_action_copy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_action_delete").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_action_reply").performClick()
        composeTestRule.waitForIdle()

        // Long press sticker message -> opens dialog with Favorite sticker action -> tap Favorite
        composeTestRule.onNodeWithTag("message_bubble_msg_sticker_1")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("message_action_favorite_sticker").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_action_favorite_sticker").performClick()
        composeTestRule.waitForIdle()
        assertTrue(favoriteClicked)

        stickerFile.delete()
    }

    @Test
    fun composerTrays_switchSmoothlyBetweenStickersAttachmentsAndKeyboard() {
        val nowClock = MutableStateFlow(System.currentTimeMillis())

        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_2",
                        username = "Peer",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP"
                    ),
                    messages = emptyList(),
                    currentMessageLimit = 150,
                    presence = PeerPresence.ONLINE,
                    isPeerTyping = false,
                    activeTransfers = emptyList(),
                    favoriteStickers = emptyList(),
                    nowClockFlow = nowClock,
                    voiceRecordingState = VoiceRecordingState(),
                    voicePlaybackState = VoicePlaybackState(),
                    showBackButton = true,
                    onBack = {},
                    onLoadMoreMessages = {},
                    onSendText = { _, _ -> },
                    onToggleReaction = { _, _ -> },
                    onTypingChanged = {},
                    onSendPhotoUri = { _, _ -> },
                    onSendVideoUri = { _, _ -> },
                    onSendDocumentUri = { _, _ -> },
                    onSendStickerStream = { _, _, _, _ -> },
                    onCheckStickerFavorited = { false },
                    onToggleFavoriteSticker = {},
                    onRemoveFavoriteSticker = {},
                    onSendFavoriteSticker = { _, _ -> },
                    onStartVoiceRecording = {},
                    onCancelVoiceRecording = {},
                    onSendVoiceRecording = {},
                    onToggleVoicePlayback = { _, _ -> },
                    onOpenAttachment = { _, _ -> },
                    onCancelTransfer = {},
                    onRetryDownload = {},
                    onSetSelfDestruct = {},
                    onVerifyPeerKey = {},
                    onReconnect = {},
                    onDeleteMessage = {},
                    onDeleteConversation = {},
                    onNotify = {}
                )
            }
        }

        // Open Stickers tray
        composeTestRule.onNodeWithTag("chat_stickers_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("stickers_tray").assertIsDisplayed()

        // Tap Stickers/Keyboard button to return to keyboard: tray stays mounted while keyboard rises
        composeTestRule.onNodeWithTag("chat_stickers_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(150)
        composeTestRule.onNodeWithTag("stickers_tray").assertIsDisplayed()

        // Switch to Attachments tray and verify 3 square tiles + 1 rectangular list option
        composeTestRule.onNodeWithTag("chat_attach_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("attachments_tray").assertIsDisplayed()
        composeTestRule.onNodeWithTag("attach_tile_camera").assertIsDisplayed()
        composeTestRule.onNodeWithTag("attach_tile_gallery").assertIsDisplayed()
        composeTestRule.onNodeWithTag("attach_tile_files").assertIsDisplayed()
        composeTestRule.onNodeWithTag("attach_option_self_destruct").assertIsDisplayed()

        // Tapping self-destruct option closes tray and opens Self-destruct dialog
        composeTestRule.onNodeWithTag("attach_option_self_destruct").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Self-destruct").assertIsDisplayed()
        composeTestRule.onNodeWithText("Close").performClick()
        composeTestRule.waitForIdle()

        // Open and close Attachments tray with the rotating + / x button
        composeTestRule.onNodeWithTag("chat_attach_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("attachments_tray").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_attach_button").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun imageMessagesAdjustSizeToAspectRatio_andStickersUseFixedSize() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val portraitFile = File(context.cacheDir, "portrait_img.png").apply {
            val bmp = Bitmap.createBitmap(108, 192, Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        val landscapeFile = File(context.cacheDir, "landscape_img.png").apply {
            val bmp = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        val stickerSmallFile = File(context.cacheDir, "sticker_sm.png").apply {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        val stickerLargeFile = File(context.cacheDir, "sticker_lg.png").apply {
            val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }

        val nowClock = MutableStateFlow(System.currentTimeMillis())
        val messages = listOf(
            MessageEntity(
                messageId = "img_portrait",
                peerId = "peer_3",
                isOutgoing = true,
                type = MessageType.IMAGE.name,
                mediaLocalPath = portraitFile.absolutePath,
                fileName = "portrait.png",
                mimeType = "image/png",
                transferProgress = 100,
                status = DeliveryStatus.SENT.name
            ),
            MessageEntity(
                messageId = "img_landscape",
                peerId = "peer_3",
                isOutgoing = false,
                type = MessageType.IMAGE.name,
                mediaLocalPath = landscapeFile.absolutePath,
                fileName = "landscape.png",
                mimeType = "image/png",
                transferProgress = 100,
                status = DeliveryStatus.READ.name
            ),
            MessageEntity(
                messageId = "stk_small",
                peerId = "peer_3",
                isOutgoing = true,
                type = MessageType.STICKER.name,
                mediaLocalPath = stickerSmallFile.absolutePath,
                fileName = "s1.png",
                mimeType = "image/png",
                transferProgress = 100,
                status = DeliveryStatus.SENT.name
            ),
            MessageEntity(
                messageId = "stk_large",
                peerId = "peer_3",
                isOutgoing = false,
                type = MessageType.STICKER.name,
                mediaLocalPath = stickerLargeFile.absolutePath,
                fileName = "s2.png",
                mimeType = "image/png",
                transferProgress = 100,
                status = DeliveryStatus.READ.name
            )
        )

        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_3",
                        username = "Peer",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP"
                    ),
                    messages = messages,
                    currentMessageLimit = 150,
                    presence = PeerPresence.ONLINE,
                    isPeerTyping = false,
                    activeTransfers = emptyList(),
                    favoriteStickers = emptyList(),
                    nowClockFlow = nowClock,
                    voiceRecordingState = VoiceRecordingState(),
                    voicePlaybackState = VoicePlaybackState(),
                    showBackButton = true,
                    onBack = {},
                    onLoadMoreMessages = {},
                    onSendText = { _, _ -> },
                    onToggleReaction = { _, _ -> },
                    onTypingChanged = {},
                    onSendPhotoUri = { _, _ -> },
                    onSendVideoUri = { _, _ -> },
                    onSendDocumentUri = { _, _ -> },
                    onSendStickerStream = { _, _, _, _ -> },
                    onCheckStickerFavorited = { false },
                    onToggleFavoriteSticker = {},
                    onRemoveFavoriteSticker = {},
                    onSendFavoriteSticker = { _, _ -> },
                    onStartVoiceRecording = {},
                    onCancelVoiceRecording = {},
                    onSendVoiceRecording = {},
                    onToggleVoicePlayback = { _, _ -> },
                    onOpenAttachment = { _, _ -> },
                    onCancelTransfer = {},
                    onRetryDownload = {},
                    onSetSelfDestruct = {},
                    onVerifyPeerKey = {},
                    onReconnect = {},
                    onDeleteMessage = {},
                    onDeleteConversation = {},
                    onNotify = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("chat_messages_list")
            .performScrollToNode(hasTestTag("message_bubble_img_portrait"))
        composeTestRule.waitUntil(timeoutMillis = 5000L) {
            runCatching {
                val b = composeTestRule.onNodeWithTag("message_bubble_img_portrait").getUnclippedBoundsInRoot()
                b.height > b.width
            }.getOrDefault(false)
        }
        val portraitBounds = composeTestRule.onNodeWithTag("message_bubble_img_portrait").getUnclippedBoundsInRoot()

        composeTestRule.onNodeWithTag("chat_messages_list")
            .performScrollToNode(hasTestTag("message_bubble_img_landscape"))
        composeTestRule.waitUntil(timeoutMillis = 5000L) {
            runCatching {
                val b = composeTestRule.onNodeWithTag("message_bubble_img_landscape").getUnclippedBoundsInRoot()
                b.width > b.height
            }.getOrDefault(false)
        }
        val landscapeBounds = composeTestRule.onNodeWithTag("message_bubble_img_landscape").getUnclippedBoundsInRoot()

        assertTrue(portraitBounds.height > portraitBounds.width)
        assertTrue(landscapeBounds.width > landscapeBounds.height)
        assertTrue(portraitBounds.height > landscapeBounds.height)
        assertTrue(portraitBounds.width < landscapeBounds.width)

        composeTestRule.onNodeWithTag("chat_messages_list")
            .performScrollToNode(hasTestTag("message_bubble_stk_small"))
        composeTestRule.waitForIdle()
        val smallStickerBounds = composeTestRule.onNodeWithTag("message_bubble_stk_small").getUnclippedBoundsInRoot()

        composeTestRule.onNodeWithTag("chat_messages_list")
            .performScrollToNode(hasTestTag("message_bubble_stk_large"))
        composeTestRule.waitForIdle()
        val largeStickerBounds = composeTestRule.onNodeWithTag("message_bubble_stk_large").getUnclippedBoundsInRoot()

        assertTrue(smallStickerBounds.width == largeStickerBounds.width)
        assertTrue(smallStickerBounds.height == largeStickerBounds.height)

        portraitFile.delete()
        landscapeFile.delete()
        stickerSmallFile.delete()
        stickerLargeFile.delete()
    }
}


