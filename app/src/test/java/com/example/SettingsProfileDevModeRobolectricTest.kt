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
import androidx.compose.ui.test.performScrollToNode
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
import com.example.ui.theme.ChatsoTheme
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

        // Updates, About, Licenses, and Developer Mode are separate standalone cards
        composeTestRule.onNodeWithTag("settings_updates_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_about_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_licenses_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_dev_mode_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_open_dev_tools").performScrollTo().performClick()
        assertTrue(devToolsOpened)
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
    fun devModeScreenRendersAllSimulatorControls() {
        val fakePeer = PeerEntity(
            peerId = "${ChatRepository.DEV_FAKE_PEER_PREFIX}1",
            username = "Alice Fake",
            publicKeyBase64 = "K",
            fingerprint = "F"
        )
        var sentDelay = -1
        composeTestRule.setContent {
            ChatsoTheme {
                DevModeScreen(
                    identity = LocalIdentity(isInitialized = true, username = "Me", peerId = "local_id_1234567890", devModeEnabled = true),
                    peers = listOf(fakePeer),
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

        composeTestRule.onNodeWithTag("dev_create_fake_peer_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_send_delayed_button").performScrollTo().performClick()
        assertEquals(5, sentDelay)
        composeTestRule.onNodeWithTag("dev_unread_burst_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dev_clear_fakes_button").performScrollTo().assertIsDisplayed()
    }
}

private val Float.dpValueCheck: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this)
