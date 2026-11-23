package com.example

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.network.ChatsoSharingShortcuts
import com.example.network.RawShareRequest
import com.example.network.ShareIntentParser
import com.example.ui.ChatsoViewModel
import com.example.ui.PendingIncomingShare
import com.example.ui.StagedSharedFile
import com.example.ui.screens.ShareTargetSheet
import com.example.ui.theme.ChatsoTheme
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharingRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `manifest resolves chatso for ACTION_SEND and ACTION_SEND_MULTIPLE`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        val singleMimeTypes = listOf("text/plain", "image/jpeg", "video/mp4", "application/pdf")
        singleMimeTypes.forEach { mime ->
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
            }
            val resolved = pm.queryIntentActivities(sendIntent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(
                "Expected MainActivity to handle ACTION_SEND for $mime",
                resolved.any { it.activityInfo?.name == MainActivity::class.java.name }
            )
        }

        val multiIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
        }
        val multiResolved = pm.queryIntentActivities(multiIntent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(
            "Expected MainActivity to handle ACTION_SEND_MULTIPLE",
            multiResolved.any { it.activityInfo?.name == MainActivity::class.java.name }
        )
    }

    @Test
    fun `shortcuts metadata is declared on MainActivity and default launcher alias`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        val mainInfo = pm.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals(R.xml.shortcuts, mainInfo.metaData?.getInt("android.app.shortcuts"))

        val defaultAliasInfo = pm.getActivityInfo(
            ComponentName(context.packageName, "com.example.MainActivityAliasDefault"),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        assertEquals(R.xml.shortcuts, defaultAliasInfo.metaData?.getInt("android.app.shortcuts"))
    }

    @Test
    fun `syncContactShortcuts publishes ranked direct share shortcuts and removes deleted peer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val peers = listOf(
            PeerEntity(
                peerId = "peer_older",
                publicKeyBase64 = "pk1",
                fingerprint = "fp1",
                username = "Alice",
                lastActivityAt = 1_000L
            ),
            PeerEntity(
                peerId = "peer_newest",
                publicKeyBase64 = "pk2",
                fingerprint = "fp2",
                username = "Bruno",
                lastActivityAt = 5_000L
            )
        )

        ChatsoSharingShortcuts.syncContactShortcuts(context, peers)

        val published = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(2, published.size)
        val byId = published.associateBy { it.id }
        val newest = byId["conv_peer_newest"]
        val older = byId["conv_peer_older"]
        assertNotNull(newest)
        assertNotNull(older)
        assertEquals(0, newest!!.rank)
        assertEquals(1, older!!.rank)
        assertEquals("Bruno", newest.shortLabel.toString())
        assertTrue(newest.categories.orEmpty().contains(ChatsoSharingShortcuts.SHARE_TARGET_CATEGORY))
        assertTrue(newest.categories.orEmpty().contains(ChatsoSharingShortcuts.CONVERSATION_CATEGORY))

        ChatsoSharingShortcuts.removeContactShortcut(context, "peer_newest")
        val afterRemove = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertFalse(afterRemove.any { it.id == "conv_peer_newest" })
        assertTrue(afterRemove.any { it.id == "conv_peer_older" })
    }

    @Test
    fun `share intent parser handles text, stream, multiple streams, clipData, direct share, and history flag`() {
        val uri1 = Uri.parse("content://example.provider/media/1")
        val uri2 = Uri.parse("content://example.provider/media/2")

        val textIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Link title")
            putExtra(Intent.EXTRA_TEXT, "https://example.org/post")
            putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "conv_peer_42")
        }
        val parsedText = ShareIntentParser.parse(textIntent)
        assertNotNull(parsedText)
        assertEquals("peer_42", parsedText?.targetPeerId)
        assertEquals("Link title\nhttps://example.org/post", parsedText?.sharedText)
        assertTrue(parsedText?.streamUris.orEmpty().isEmpty())

        ShareIntentParser.markConsumed(textIntent)
        assertNull(ShareIntentParser.parse(textIntent))

        val multiIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
            clipData = ClipData.newRawUri("extra", uri1)
        }
        val parsedMulti = ShareIntentParser.parse(multiIntent)
        assertNotNull(parsedMulti)
        assertNull(parsedMulti?.targetPeerId)
        assertEquals(listOf(uri1, uri2), parsedMulti?.streamUris)

        val historyIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "stale")
            addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        }
        assertNull(ShareIntentParser.parse(historyIntent))
    }

    @Test
    fun `viewModel stages shared file and delivers share payload to multiple peers`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ChatsoApplication>()
        val repo = app.container.repository
        val peerA = repo.devCreateFakePeer("Alice Share")
        val peerB = repo.devCreateFakePeer("Bob Share")

        val sampleFile = File(app.cacheDir, "external_sample.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }
        val viewModel = ChatsoViewModel(app)
        viewModel.handleIncomingShareRequest(
            RawShareRequest(
                targetPeerId = peerA.peerId,
                sharedText = "Check this photo!",
                streamUris = listOf(Uri.fromFile(sampleFile))
            )
        )

        var readyShare = viewModel.pendingIncomingShare.value
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < deadline &&
            (readyShare == null || readyShare.isLoadingMedia)
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(25)
            readyShare = viewModel.pendingIncomingShare.value
        }

        assertNotNull(readyShare)
        assertFalse(readyShare!!.isLoadingMedia)
        assertEquals(peerA.peerId, readyShare.directTargetPeerId)
        assertEquals(1, readyShare.sharedFiles.size)
        val stagedFile = readyShare.sharedFiles.first().file
        assertTrue(stagedFile.exists())
        assertEquals(MessageType.IMAGE, readyShare.sharedFiles.first().messageType)

        viewModel.sendPendingShare(
            peerIds = setOf(peerA.peerId, peerB.peerId),
            captionOrText = "Check this photo!"
        )

        val clearDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < clearDeadline && viewModel.pendingIncomingShare.value != null) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(25)
        }
        assertNull(viewModel.pendingIncomingShare.value)
        assertFalse("Staged temporary cache file should be cleaned up", stagedFile.exists())

        val msgsA = repo.observeMessages(peerA.peerId, 20).first()
        val msgsB = repo.observeMessages(peerB.peerId, 20).first()
        assertTrue(msgsA.any { it.type == MessageType.IMAGE.name })
        assertTrue(msgsA.any { it.type == MessageType.TEXT.name && it.textContent == "Check this photo!" })
        assertTrue(msgsB.any { it.type == MessageType.IMAGE.name })
        assertTrue(msgsB.any { it.type == MessageType.TEXT.name && it.textContent == "Check this photo!" })
    }

    @Test
    fun `share target sheet handles direct confirmation switch to picker and multi send`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDoc = File(context.cacheDir, "notes.txt").apply {
            writeText("hello")
        }
        val peers = listOf(
            PeerEntity(peerId = "p1", publicKeyBase64 = "k1", fingerprint = "fp1", username = "Alice", lastActivityAt = 3000L),
            PeerEntity(peerId = "p2", publicKeyBase64 = "k2", fingerprint = "fp2", username = "Bruno", lastActivityAt = 2000L)
        )
        val share = PendingIncomingShare(
            id = "share_1",
            directTargetPeerId = "p1",
            sharedText = "Shared link",
            sharedFiles = listOf(
                StagedSharedFile(
                    file = tempDoc,
                    fileName = "notes.txt",
                    mimeType = "text/plain",
                    sizeBytes = tempDoc.length(),
                    messageType = MessageType.FILE
                )
            )
        )
        var capturedTargets: Set<String>? = null
        var capturedMessage: String? = null

        composeTestRule.setContent {
            ChatsoTheme {
                ShareTargetSheet(
                    share = share,
                    peers = peers,
                    connectedPeerIds = setOf("p1"),
                    foregroundPeerIds = setOf("p1"),
                    onSend = { ids, msg ->
                        capturedTargets = ids
                        capturedMessage = msg
                    },
                    onGoToConnect = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("share_confirm_send_button").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("share_change_contact_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("share_peer_row_p2").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("share_send_selected_button").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.waitForIdle()

        assertEquals(setOf("p1", "p2"), capturedTargets)
        assertEquals("Shared link", capturedMessage)
    }
}
