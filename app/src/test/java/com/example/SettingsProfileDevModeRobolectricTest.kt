package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.LocalIdentityStore
import com.example.data.MediaStorageHelper
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.media.VoicePlaybackState
import com.example.media.VoiceRecordingState
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.DevModeScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsScreen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import com.example.data.ActiveTransferInfo
import com.example.data.BridgeRouteInfo
import com.example.data.BridgeTelemetry
import com.example.data.ThemePalette
import com.example.data.TransferPhase
import com.example.network.ChatsoNotificationHelper
import com.example.ui.theme.ChatsoTheme
import com.example.ui.theme.spec
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsProfileDevModeRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun profileAndSettingsAreSeparated_andAboutUpdatesLicensesAreDistinctCards() {
        val identity = LocalIdentity(
            isInitialized = true,
            username = "Tester",
            peerId = "peer_local_1234567890",
            publicKeyBase64 = "PUBKEY",
            fingerprint = "AA:BB:CC:DD",
            devModeEnabled = true
        )
        var devToolsOpened = false

        composeTestRule.setContent {
            ChatsoTheme {
                SettingsScreen(
                    identity = identity,
                    endpoints = emptyList(),
                    boundPort = 42850,
                    onToggleDiscoverable = {},
                    onToggleBlockUnknown = {},
                    onToggleBackgroundConnection = {},
                    onUpdateListenPort = {},
                    onChangeTheme = {},
                    onChangePalette = {},
                    onToggleThemeAdvanced = {},
                    onChangeLanguage = {},
                    onCheckUpdates = {},
                    isCheckingUpdates = false,
                    onToggleDevMode = {},
                    onOpenDevTools = { devToolsOpened = true }
                )
            }
        }

        // Settings screen does NOT contain profile username input
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("profile_username_input")).fetchSemanticsNodes().isEmpty()
        )

        // Theme Auto/Light/Dark are visible initially, while palette & full theme are collapsed
        composeTestRule.onNodeWithTag("settings_theme_system").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_theme_light").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_theme_dark").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("settings_palette_purple")).fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithTag("settings_theme_expand_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_palette_purple").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_theme_advanced_switch").performScrollTo().assertIsDisplayed()

        // Language options are collapsed initially and visible after expanding
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("settings_lang_pt")).fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithTag("settings_language_expand_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_lang_pt").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_lang_zh").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_lang_ja").performScrollTo().assertIsDisplayed()

        // Network & Port + Bridge mode are collapsed initially and visible after expanding
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("settings_bridge_switch")).fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("settings_port_input")).fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithTag("settings_network_expand_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_bridge_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_port_input").performScrollTo().assertIsDisplayed()

        // Updates, About, Licenses, and discreet Developer Mode live inside one unified card
        composeTestRule.onNodeWithTag("settings_about_section_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_about_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_updates_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_licenses_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_dev_mode_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_dev_mode_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_open_dev_tools").performScrollTo().performClick()
        assertTrue(devToolsOpened)
    }

    @Test
    fun notificationAccentColorAlwaysFollowsThemePalette() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        ChatsoNotificationHelper.syncThemeConfig(
            context = context,
            palette = ThemePalette.BLUE,
            fullThemeEnabled = false
        )
        assertEquals(
            ThemePalette.BLUE.spec().light.primary.toArgb(),
            ChatsoNotificationHelper.resolveAccentColor(context)
        )

        ChatsoNotificationHelper.syncThemeConfig(
            context = context,
            palette = ThemePalette.BLUE,
            fullThemeEnabled = true
        )
        assertEquals(
            ThemePalette.BLUE.spec().light.primary.toArgb(),
            ChatsoNotificationHelper.resolveAccentColor(context)
        )
    }

    @Test
    fun profileScreenShowsIdentityAndKeysOnly() {
        val identity = LocalIdentity(
            isInitialized = true,
            username = "Maria",
            peerId = "peer_local_999",
            publicKeyBase64 = "PUBKEY_999",
            fingerprint = "11:22:33:44"
        )

        composeTestRule.setContent {
            ChatsoTheme {
                ProfileScreen(
                    identity = identity,
                    onSaveProfile = { _, _, _ -> },
                    onNotify = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_username_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_save_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_copy_peer_id_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_copy_pubkey_button").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("settings_dev_mode_card")).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun devModeSimulatesFakePeerBackgroundNotificationsAndUnreadAnchor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        var inForeground = false
        val notifications = CopyOnWriteArrayList<Pair<String, String>>()

        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = LocalIdentityStore(context),
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { inForeground }
        )
        repo.onIncomingNotification = { peerId, senderName, preview, _ ->
            notifications.add("$peerId:$senderName" to preview)
        }

        try {
            val fakePeer = repo.devCreateFakePeer("Bot Alice")
            assertTrue(fakePeer.peerId.startsWith(ChatRepository.DEV_FAKE_PEER_PREFIX))
            assertTrue(repo.isDevSimulatedOnline(fakePeer.peerId))

            // Older read message first
            val baseTime = System.currentTimeMillis() - 10_000L
            val oldMsg = MessageEntity(
                messageId = "old_read_1",
                peerId = fakePeer.peerId,
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Older already read",
                transferProgress = 100,
                status = DeliveryStatus.READ.name,
                createdAt = baseTime,
                readAt = baseTime + 100L
            )
            dao.upsertMessage(oldMsg)

            // Simulate 2 incoming messages arriving while app is in background
            inForeground = false
            assertTrue(repo.devSimulateIncomingText(fakePeer.peerId, "New msg 1", baseTime + 2000L))
            assertTrue(repo.devSimulateIncomingText(fakePeer.peerId, "New msg 2", baseTime + 3000L))

            val peerAfterIncoming = dao.getPeerById(fakePeer.peerId)!!
            assertEquals(2, peerAfterIncoming.unreadCount)
            assertEquals(2, notifications.size)
            assertEquals("New msg 2", notifications.last().second)

            // User opens the app and enters the conversation -> capture divider BEFORE marking read
            inForeground = true
            repo.currentlyOpenPeerId = fakePeer.peerId
            repo.captureUnreadDividerAndMarkRead(fakePeer.peerId)

            val divider = repo.unreadDivider.value
            assertNotNull(divider)
            assertEquals(fakePeer.peerId, divider!!.peerId)
            assertEquals(2, divider.unreadCount)
            val unreadAnchorMsg = dao.getMessageById(divider.anchorMessageId)
            assertEquals("New msg 1", unreadAnchorMsg?.textContent)

            // Unread counter is now cleared and messages marked READ
            assertEquals(0, dao.getPeerById(fakePeer.peerId)!!.unreadCount)

            // Simulate photo and reaction
            assertTrue(repo.devSimulateIncomingMedia(fakePeer.peerId, asSticker = false))
            assertTrue(repo.devSimulateIncomingReaction(fakePeer.peerId, "❤️"))

            // Clearing fake peers removes them cleanly
            val removed = repo.devClearFakePeers()
            assertTrue(removed >= 1)
            assertNull(dao.getPeerById(fakePeer.peerId))
        } finally {
            repo.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun chatDetailScreenRendersUnreadDividerBetweenOldAndNewMessages() {
        val now = System.currentTimeMillis()
        // Remember ChatDetailScreen LazyColumn uses reverseLayout = true with DESC-ordered list:
        // index 0 = newest (bottom), index last = oldest (top).
        val messagesDesc = listOf(
            MessageEntity(
                messageId = "new_2",
                peerId = "peer_u",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Second unread message",
                status = DeliveryStatus.READ.name,
                createdAt = now - 1000L
            ),
            MessageEntity(
                messageId = "new_1",
                peerId = "peer_u",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "First unread message",
                status = DeliveryStatus.READ.name,
                createdAt = now - 2000L
            ),
            MessageEntity(
                messageId = "old_1",
                peerId = "peer_u",
                isOutgoing = true,
                type = MessageType.TEXT.name,
                textContent = "Old read message",
                status = DeliveryStatus.READ.name,
                createdAt = now - 10_000L
            )
        )
        val nowClock = MutableStateFlow(now)

        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_u",
                        username = "Peer",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP"
                    ),
                    messages = messagesDesc,
                    currentMessageLimit = 150,
                    presence = PeerPresence.ONLINE,
                    isPeerTyping = false,
                    activeTransfers = emptyList(),
                    favoriteStickers = emptyList(),
                    nowClockFlow = nowClock,
                    voiceRecordingState = VoiceRecordingState(),
                    voicePlaybackState = VoicePlaybackState(),
                    unreadDividerAnchorId = "new_1",
                    unreadDividerCount = 2,
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
            .performScrollToNode(hasTestTag("unread_messages_divider"))
        composeTestRule.onNodeWithTag("unread_messages_divider").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 new messages").assertIsDisplayed()

        val oldBounds = composeTestRule.onNodeWithTag("message_bubble_old_1").getUnclippedBoundsInRoot()
        val dividerBounds = composeTestRule.onNodeWithTag("unread_messages_divider").getUnclippedBoundsInRoot()
        val new1Bounds = composeTestRule.onNodeWithTag("message_bubble_new_1").getUnclippedBoundsInRoot()

        // Visually top-to-bottom: old message -> unread divider -> first unread message
        assertTrue(oldBounds.bottom <= dividerBounds.top + 1f.dpValueCheck)
        assertTrue(dividerBounds.bottom <= new1Bounds.top + 1f.dpValueCheck)
    }

    @Test
    fun devModeScreenRendersAllSimulatorControlsAndExcludesRealPeers() {
        val fakePeer = PeerEntity(
            peerId = "${ChatRepository.DEV_FAKE_PEER_PREFIX}1",
            username = "Alice Fake",
            publicKeyBase64 = "K",
            fingerprint = "F"
        )
        val realPeer = PeerEntity(
            peerId = "real_peer_123",
            username = "Real Bob",
            publicKeyBase64 = "K2",
            fingerprint = "F2"
        )
        var sentDelay = -1
        composeTestRule.setContent {
            ChatsoTheme {
                DevModeScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "local_id_1234567890", devModeEnabled = true),
                    peers = listOf(realPeer, fakePeer),
                    endpoints = emptyList(),
                    boundPort = 42850,
                    serverRunning = true,
                    totalMessageCount = 3,
                    devOnlineFakePeers = setOf(fakePeer.peerId),
                    devAutoReplyEnabled = true,
                    scheduledDelayRemainingSec = null,
                    onBack = {},
                    onCreateFakePeer = { _, _ -> },
                    onSendFakeIncomingText = { _, _, d -> sentDelay = d },
                    onSendFakeUnreadBurst = { _, _, _ -> },
                    onSendFakeMedia = { _, _ -> },
                    onSimulateTyping = {},
                    onSimulateReaction = {},
                    onTriggerKeyWarning = {},
                    onTogglePeerSimulatedOnline = { _, _ -> },
                    onToggleAutoReply = {},
                    onOpenPeerChat = {},
                    onClearFakePeers = {},
                    onNotify = {}
                )
            }
        }

        // Only fake contacts appear as Dev Mode targets, never real users
        composeTestRule.onNodeWithTag("dev_target_chip_${fakePeer.peerId}").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("dev_target_chip_${realPeer.peerId}")).fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithTag("dev_create_fake_peer_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_send_delayed_button").performScrollTo().performClick()
        assertEquals(5, sentDelay)
        composeTestRule.onNodeWithTag("dev_unread_burst_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_clear_fakes_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chatDetailShowsScrollToBottomButtonWhenScrolledUpAndAutoScrollsOnSend() {
        val now = System.currentTimeMillis()
        val initialMessages = (0 until 30).map { idx ->
            MessageEntity(
                messageId = "msg_$idx",
                peerId = "peer_scroll",
                isOutgoing = idx % 2 == 0,
                type = MessageType.TEXT.name,
                textContent = "Message #$idx with some text",
                status = DeliveryStatus.READ.name,
                createdAt = now - (idx * 1000L)
            )
        }
        val messagesState = mutableStateOf(initialMessages)
        val nowClock = MutableStateFlow(now)

        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_scroll",
                        username = "Peer",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP"
                    ),
                    messages = messagesState.value,
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
                    onSendText = { text, _ ->
                        val newMsg = MessageEntity(
                            messageId = "msg_sent_new",
                            peerId = "peer_scroll",
                            isOutgoing = true,
                            type = MessageType.TEXT.name,
                            textContent = text,
                            status = DeliveryStatus.SENT.name,
                            createdAt = now + 5000L
                        )
                        messagesState.value = listOf(newMsg) + messagesState.value
                    },
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

        // At bottom initially -> scroll-to-bottom button is hidden
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("chat_scroll_to_bottom_button")).fetchSemanticsNodes().isEmpty()
        )

        // Scroll up to older messages -> button appears
        composeTestRule.onNodeWithTag("chat_messages_list").performScrollToIndex(20)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chat_scroll_to_bottom_button").assertIsDisplayed()

        // Tap scroll-to-bottom button -> returns to bottom and hides button
        composeTestRule.onNodeWithTag("chat_scroll_to_bottom_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("chat_scroll_to_bottom_button")).fetchSemanticsNodes().isEmpty()
        )

        // Scroll up again, then send a message -> auto-scrolls back to bottom showing new message
        composeTestRule.onNodeWithTag("chat_messages_list").performScrollToIndex(20)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chat_scroll_to_bottom_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("chat_message_input").performTextInput("Hello from top")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("message_bubble_msg_sent_new").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("chat_scroll_to_bottom_button")).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun chatHeaderOpensContactProfileAndEnlargedAvatarOpensFullscreenViewer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val avatarFile = java.io.File.createTempFile("peer_avatar", ".png", context.cacheDir).apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }
        try {
            val nowClock = MutableStateFlow(System.currentTimeMillis())
            val peer = PeerEntity(
                peerId = "peer_profile_view",
                username = "Carla VPN",
                publicKeyBase64 = "CARLA_KEY",
                fingerprint = "AA:11:BB:22",
                avatarPath = avatarFile.absolutePath,
                lastIp = "10.8.0.5",
                lastPort = 42850,
                viaBridgePeerId = "bridge_node_b"
            )

            composeTestRule.setContent {
                ChatsoTheme {
                    ChatDetailScreen(
                        identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                        peer = peer,
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

            composeTestRule.onNodeWithTag("chat_header_profile_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("contact_profile_screen").assertIsDisplayed()
            assertTrue(
                composeTestRule.onAllNodes(hasTestTag("contact_profile_dialog")).fetchSemanticsNodes().isEmpty()
            )
            assertTrue(
                composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("Tap photo to view")).fetchSemanticsNodes().isEmpty()
            )
            composeTestRule.onNodeWithText("10.8.0.5:42850").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("AA:11:BB:22").performScrollTo().assertIsDisplayed()

            composeTestRule.onNodeWithTag("contact_profile_avatar_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("fullscreen_image_viewer").assertIsDisplayed()
        } finally {
            avatarFile.delete()
        }
    }

    @Test
    fun chatDetailVideoBubbleShowsCenteredCircularTransferPhaseAndEta() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val videoFile = java.io.File.createTempFile("vid_transfer", ".mp4", context.cacheDir).apply {
            writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70))
        }
        try {
            val nowClock = MutableStateFlow(System.currentTimeMillis())
            val msgId = "vid_msg_1"
            val msg = MessageEntity(
                messageId = msgId,
                peerId = "peer_video",
                isOutgoing = true,
                type = MessageType.VIDEO.name,
                textContent = "Sending video…",
                mediaLocalPath = videoFile.absolutePath,
                fileName = "clip.mp4",
                transferProgress = 42,
                status = DeliveryStatus.SENDING.name,
                mediaWidth = 1080,
                mediaHeight = 1920
            )
            val transferState = mutableStateOf(
                listOf(
                    ActiveTransferInfo(
                        messageId = msgId,
                        peerId = "peer_video",
                        fileName = "clip.mp4",
                        progress = 42,
                        isOutgoing = true,
                        totalBytes = 1_500_000L,
                        phase = TransferPhase.COMPRESSING,
                        etaSeconds = 12L
                    )
                )
            )
            var cancelledId: String? = null

            composeTestRule.setContent {
                ChatsoTheme {
                    ChatDetailScreen(
                        identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                        peer = PeerEntity(
                            peerId = "peer_video",
                            username = "Peer",
                            publicKeyBase64 = "KEY",
                            fingerprint = "FP"
                        ),
                        messages = listOf(msg),
                        currentMessageLimit = 150,
                        presence = PeerPresence.ONLINE,
                        isPeerTyping = false,
                        activeTransfers = transferState.value,
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
                        onCancelTransfer = { cancelledId = it },
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

            composeTestRule.onNodeWithTag("chat_cancel_transfer_$msgId").assertIsDisplayed()
            composeTestRule.onNodeWithTag("chat_transfer_status_$msgId").assertIsDisplayed()
            composeTestRule.onNodeWithText("Compressing 42% · ~12s").assertIsDisplayed()

            transferState.value = listOf(
                ActiveTransferInfo(
                    messageId = msgId,
                    peerId = "peer_video",
                    fileName = "clip.mp4",
                    progress = 68,
                    isOutgoing = true,
                    totalBytes = 800_000L,
                    phase = TransferPhase.UPLOADING,
                    etaSeconds = 4L
                )
            )
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Uploading 68% · ~4s").assertIsDisplayed()

            composeTestRule.onNodeWithTag("chat_cancel_transfer_$msgId").performClick()
            assertEquals(msgId, cancelledId)
        } finally {
            videoFile.delete()
        }
    }

    @Test
    fun scrolledUpChatShowsNewMessagesAlertAndOnlyMarksVisibleMessagesRead() {
        val now = System.currentTimeMillis()
        // 30 old READ messages + 2 incoming DELIVERED (unread) messages at index 0..1 (newest at bottom)
        val messagesDesc = listOf(
            MessageEntity(
                messageId = "unread_2",
                peerId = "peer_unseen",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Second unseen incoming",
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now + 2000L
            ),
            MessageEntity(
                messageId = "unread_1",
                peerId = "peer_unseen",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "First unseen incoming",
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now + 1000L
            )
        ) + (0 until 30).map { idx ->
            MessageEntity(
                messageId = "old_$idx",
                peerId = "peer_unseen",
                isOutgoing = idx % 2 == 0,
                type = MessageType.TEXT.name,
                textContent = "Older message #$idx",
                status = DeliveryStatus.READ.name,
                createdAt = now - ((idx + 1) * 1000L)
            )
        }
        val messagesState = mutableStateOf(messagesDesc)
        val markedReadIds = mutableSetOf<String>()
        val atBottomHistory = mutableListOf<Boolean>()
        val nowClock = MutableStateFlow(now)

        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_unseen",
                        username = "Peer",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP"
                    ),
                    messages = messagesState.value,
                    currentMessageLimit = 150,
                    presence = PeerPresence.ONLINE,
                    isPeerTyping = false,
                    activeTransfers = emptyList(),
                    favoriteStickers = emptyList(),
                    nowClockFlow = nowClock,
                    voiceRecordingState = VoiceRecordingState(),
                    voicePlaybackState = VoicePlaybackState(),
                    unreadDividerAnchorId = "unread_1",
                    unreadDividerCount = 2,
                    showBackButton = true,
                    onBack = {},
                    onAtBottomChanged = { atBottomHistory.add(it) },
                    onMarkVisibleMessagesRead = { ids ->
                        markedReadIds.addAll(ids)
                        messagesState.value = messagesState.value.map { msg ->
                            if (ids.contains(msg.messageId)) msg.copy(status = DeliveryStatus.READ.name) else msg
                        }
                    },
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

        // Scroll to the top of history first, then add an unseen incoming message while scrolled up
        composeTestRule.onNodeWithTag("chat_messages_list").performScrollToIndex(28)
        composeTestRule.waitForIdle()
        assertTrue(atBottomHistory.lastOrNull() == false)
        markedReadIds.clear()

        val newestUnseen = listOf(
            MessageEntity(
                messageId = "unread_live_2",
                peerId = "peer_unseen",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Live unseen 2",
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now + 4000L
            ),
            MessageEntity(
                messageId = "unread_live_1",
                peerId = "peer_unseen",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Live unseen 1",
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now + 3000L
            )
        )
        messagesState.value = newestUnseen + messagesState.value
        composeTestRule.waitForIdle()

        // Must NOT mark unread_live_1 / unread_live_2 read while scrolled up, and MUST show bottom-center alert
        assertTrue(!markedReadIds.contains("unread_live_1"))
        assertTrue(!markedReadIds.contains("unread_live_2"))
        composeTestRule.onNodeWithTag("chat_new_messages_alert").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 new messages").assertIsDisplayed()

        // Tapping the alert scrolls to bottom and marks both visible messages READ
        composeTestRule.onNodeWithTag("chat_new_messages_alert").performClick()
        composeTestRule.waitForIdle()

        assertTrue(markedReadIds.contains("unread_live_1"))
        assertTrue(markedReadIds.contains("unread_live_2"))
        assertTrue(atBottomHistory.lastOrNull() == true)
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("chat_new_messages_alert")).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun composerInputHeightMatchesVoiceAndSendButtons() {
        val nowClock = MutableStateFlow(System.currentTimeMillis())
        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_height",
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

        val inputBounds = composeTestRule.onNodeWithTag("chat_composer_input_surface").getUnclippedBoundsInRoot()
        val voiceBounds = composeTestRule.onNodeWithTag("chat_voice_button").getUnclippedBoundsInRoot()
        val inputHeight = inputBounds.bottom - inputBounds.top
        val voiceHeight = voiceBounds.bottom - voiceBounds.top
        assertTrue("Expected composer input height ($inputHeight) >= voice button ($voiceHeight)", inputHeight >= voiceHeight - 1f.dpValueCheck)
    }

    @Test
    fun bridgedChatShowsNoticeBannerWithBridgeName() {
        val nowClock = MutableStateFlow(System.currentTimeMillis())
        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_charlie",
                        username = "Charlie",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP",
                        viaBridgePeerId = "peer_bridge_b"
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
                    bridgePeerName = "BridgeB",
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

        composeTestRule.onNodeWithTag("chat_bridge_notice_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chatting via bridge (BridgeB) · E2EE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Online · Via bridge").assertIsDisplayed()
    }

    @Test
    fun devModeDisplaysBridgeTelemetryAndActiveRoutes() {
        val telemetry = BridgeTelemetry(
            isEnabled = true,
            directSessionCount = 2,
            directPeerNames = listOf("Alice", "Charlie"),
            advertisedPeerCount = 1,
            advertisedPeerNames = listOf("Charlie"),
            relayedFramesTotal = 14L,
            relayedBytesTotal = 2048L,
            failedRelaysTotal = 1L,
            activeRoutes = listOf(
                BridgeRouteInfo(
                    fromPeerId = "a",
                    fromName = "Alice",
                    toPeerId = "c",
                    toName = "Charlie",
                    relayedFrames = 9L,
                    relayedBytes = 1536L,
                    lastRelayedAt = System.currentTimeMillis()
                )
            )
        )

        composeTestRule.setContent {
            ChatsoTheme {
                DevModeScreen(
                    identity = LocalIdentity(
                        isInitialized = true,
                        username = "BridgeB",
                        peerId = "local_bridge_id_1234",
                        devModeEnabled = true,
                        bridgeEnabled = true
                    ),
                    peers = emptyList(),
                    endpoints = emptyList(),
                    boundPort = 42850,
                    serverRunning = true,
                    totalMessageCount = 12,
                    devOnlineFakePeers = emptySet(),
                    devAutoReplyEnabled = false,
                    scheduledDelayRemainingSec = null,
                    bridgeTelemetry = telemetry,
                    onBack = {},
                    onCreateFakePeer = { _, _ -> },
                    onSendFakeIncomingText = { _, _, _ -> },
                    onSendFakeUnreadBurst = { _, _, _ -> },
                    onSendFakeMedia = { _, _ -> },
                    onSimulateTyping = {},
                    onSimulateReaction = {},
                    onTriggerKeyWarning = {},
                    onTogglePeerSimulatedOnline = { _, _ -> },
                    onToggleAutoReply = {},
                    onOpenPeerChat = {},
                    onClearFakePeers = {},
                    onNotify = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("dev_bridge_section_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_bridge_mode_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_create_bridged_fake_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_run_bridge_test_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_reset_bridge_stats_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("ACTIVE").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2 (Alice, Charlie)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("1 (Charlie)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("14").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice → Charlie").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun incomingMessagesAutoScrollWhenAtBottomAndStayPutWhenScrolledUp() {
        val now = System.currentTimeMillis()
        val initialMessages = (0 until 30).map { idx ->
            MessageEntity(
                messageId = "base_$idx",
                peerId = "peer_autoscroll",
                isOutgoing = idx % 2 == 0,
                type = MessageType.TEXT.name,
                textContent = "Base message #$idx",
                status = DeliveryStatus.READ.name,
                createdAt = now - (idx * 1000L)
            )
        }
        val messagesState = mutableStateOf(initialMessages)
        val atBottomHistory = mutableListOf<Boolean>()
        val nowClock = MutableStateFlow(now)

        composeTestRule.setContent {
            ChatsoTheme {
                ChatDetailScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "me"),
                    peer = PeerEntity(
                        peerId = "peer_autoscroll",
                        username = "Peer",
                        publicKeyBase64 = "KEY",
                        fingerprint = "FP"
                    ),
                    messages = messagesState.value,
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
                    onAtBottomChanged = { atBottomHistory.add(it) },
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

        composeTestRule.waitForIdle()
        assertTrue(atBottomHistory.lastOrNull() == true)

        // Incoming short message 1 arrives while user is at the bottom -> must scroll to bottom automatically
        messagesState.value = listOf(
            MessageEntity(
                messageId = "incoming_live_1",
                peerId = "peer_autoscroll",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Oi",
                status = DeliveryStatus.READ.name,
                createdAt = now + 1000L
            )
        ) + messagesState.value
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("message_bubble_incoming_live_1").assertIsDisplayed()
        assertTrue(atBottomHistory.none { !it })
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("chat_scroll_to_bottom_button")).fetchSemanticsNodes().isEmpty()
        )

        // Incoming message 2 arrives right after -> must also scroll to bottom automatically
        messagesState.value = listOf(
            MessageEntity(
                messageId = "incoming_live_2",
                peerId = "peer_autoscroll",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Tudo bem por aí?",
                status = DeliveryStatus.READ.name,
                createdAt = now + 2000L
            )
        ) + messagesState.value
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("message_bubble_incoming_live_2").assertIsDisplayed()
        assertTrue(atBottomHistory.none { !it })
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("chat_scroll_to_bottom_button")).fetchSemanticsNodes().isEmpty()
        )

        // Now user scrolls up to older messages -> incoming message must NOT yank viewport to bottom
        composeTestRule.onNodeWithTag("chat_messages_list").performScrollToIndex(20)
        composeTestRule.waitForIdle()
        assertTrue(atBottomHistory.lastOrNull() == false)

        messagesState.value = listOf(
            MessageEntity(
                messageId = "incoming_live_3",
                peerId = "peer_autoscroll",
                isOutgoing = false,
                type = MessageType.TEXT.name,
                textContent = "Nova mensagem enquanto voce esta la em cima",
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now + 3000L
            )
        ) + messagesState.value
        composeTestRule.waitForIdle()

        assertTrue(atBottomHistory.lastOrNull() == false)
        composeTestRule.onNodeWithTag("chat_new_messages_alert").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("message_bubble_incoming_live_3")).fetchSemanticsNodes().isEmpty()
        )
    }
}

private val Float.dpValueCheck: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this)
