package com.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChatDatabase
import com.example.data.DeliveryStatus
import com.example.data.FavoriteStickerEntity
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.PackStickerEntity
import com.example.data.PeerEntity
import com.example.data.StickerPackArchiveHelper
import com.example.data.StickerPackEntity
import com.example.data.StickerPackProbe
import com.example.media.VoicePlaybackState
import com.example.network.RawShareRequest
import com.example.ui.ChatsoViewModel
import com.example.ui.PendingIncomingShare
import com.example.ui.PendingPackImportUiState
import com.example.ui.StagedSharedFile
import com.example.ui.components.ChatMessageBubbleItem
import com.example.ui.components.ImportStickerPackDialog
import com.example.ui.components.StickersTrayContent
import com.example.ui.screens.ShareTargetSheet
import com.example.ui.theme.ChatsoTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
class StickerPackRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeStaticWebpBytes(tag: Byte): ByteArray {
        return byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x20, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
            0x0C, 0x00, 0x00, 0x00,
            tag, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B
        )
    }

    private fun makeAnimatedWebpBytes(tag: Byte): ByteArray {
        return byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x28, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'X'.code.toByte(),
            0x0A, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x0F, 0x00, 0x00,
            'A'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte(),
            tag, 0x00, 0x00, 0x00
        )
    }

    private fun makePngBytes(tag: Byte): ByteArray {
        return byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            tag, 0x01, 0x02, 0x03
        )
    }

    private fun buildZipArchive(file: File, entries: List<Pair<String, ByteArray>>): File {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        file.parentFile?.mkdirs()
        file.writeBytes(baos.toByteArray())
        return file
    }

    @Test
    fun `helper probes and extracts whatsapp enc zip with metadata animated and static webp`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encFile = File(context.cacheDir, "12345678-1234-1234-1234-1234567890ab_memes_legais.enc")
        val metadataJson = """
            {
              "name": "Memes Legais",
              "publisher": "Chatso Creator",
              "tray_image_file": "tray.png",
              "stickers": [
                {"image_file": "02_anim.webp"},
                {"image_file": "01_static.webp"}
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        buildZipArchive(
            encFile,
            listOf(
                "metadata.json" to metadataJson,
                "tray.png" to makePngBytes(0x77),
                "01_static.webp" to makeStaticWebpBytes(0x11),
                "02_anim.webp" to makeAnimatedWebpBytes(0x22),
                "../../evil_traversal.webp" to makeStaticWebpBytes(0x33)
            )
        )

        val probe = StickerPackArchiveHelper.probePackArchive(encFile, includePreviews = true)
        assertNotNull(probe)
        assertEquals("Memes Legais", probe!!.suggestedName)
        assertEquals("Chatso Creator", probe.author)
        assertEquals(2, probe.stickerCount)
        assertEquals(2, probe.previewStickers.size)

        val destRoot = File(context.cacheDir, "extracted_packs_test").apply {
            deleteRecursively()
            mkdirs()
        }
        val imported = StickerPackArchiveHelper.importPackToDirectory(encFile, destRoot)
        assertNotNull(imported)
        assertEquals("Memes Legais", imported!!.pack.name)
        assertEquals("Chatso Creator", imported.pack.author)
        val iconPath = imported.pack.iconPath
        assertNotNull(iconPath)
        assertTrue(File(iconPath!!).exists())
        assertEquals(2, imported.stickers.size)
        assertTrue(imported.stickers.all { File(it.localPath).exists() && it.mimeType == "image/webp" })
        // Animated sticker listed first in metadata.json
        assertEquals(0, imported.stickers[0].sortOrder)
        assertTrue(File(imported.stickers[0].localPath).readBytes().contentEquals(makeAnimatedWebpBytes(0x22)))

        encFile.delete()
        destRoot.deleteRecursively()
    }

    @Test
    fun `helper falls back to cleaned filename without metadata and ignores non sticker archives`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val noMetaEnc = File(context.cacheDir, "abcd1234-1111-2222-3333-444455556666_Gatinhos_Fofos.enc")
        buildZipArchive(
            noMetaEnc,
            listOf(
                "s1.webp" to makeStaticWebpBytes(0x01),
                "s2.webp" to makeAnimatedWebpBytes(0x02)
            )
        )

        val probe = StickerPackArchiveHelper.probePackArchive(noMetaEnc, displayNameHint = noMetaEnc.name)
        assertNotNull(probe)
        assertEquals("Gatinhos Fofos", probe!!.suggestedName)
        assertEquals(2, probe.stickerCount)

        val nonStickerZip = File(context.cacheDir, "docs_only.zip")
        buildZipArchive(
            nonStickerZip,
            listOf("readme.txt" to "hello".toByteArray())
        )
        assertNull(StickerPackArchiveHelper.probePackArchive(nonStickerZip))

        noMetaEnc.delete()
        nonStickerZip.delete()
    }

    @Test
    fun `viewmodel share detection imports pack and supports rename favorite remove and delete`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        dao.observeStickerPacks().first().forEach { pack ->
            dao.deletePackStickersByPackId(pack.packId)
            dao.deleteStickerPackById(pack.packId)
        }
        dao.observeFavoriteStickers().first().forEach { fav ->
            dao.deleteFavoriteSticker(fav.stickerId)
        }

        val shareEnc = File(context.cacheDir, "Pacote_Compartilhado.enc")
        buildZipArchive(
            shareEnc,
            listOf(
                "title.txt" to "Pacote Alpha".toByteArray(),
                "a1.webp" to makeStaticWebpBytes(0x41),
                "a2.webp" to makeAnimatedWebpBytes(0x42)
            )
        )

        val app = context.applicationContext as ChatsoApplication
        val vm = ChatsoViewModel(app)

        vm.handleIncomingShareRequest(
            RawShareRequest(
                targetPeerId = null,
                sharedText = null,
                streamUris = listOf(Uri.fromFile(shareEnc))
            )
        )

        var staged = vm.pendingIncomingShare.value
        val deadline = System.currentTimeMillis() + 5_000L
        while ((staged == null || staged.isLoadingMedia) && System.currentTimeMillis() < deadline) {
            delay(50L)
            shadowOf(Looper.getMainLooper()).idle()
            staged = vm.pendingIncomingShare.value
        }
        assertNotNull(staged)
        assertEquals(1, staged!!.sharedFiles.size)
        val stagedFile = staged.sharedFiles.first()
        assertNotNull(stagedFile.detectedStickerPack)
        assertEquals("Pacote Alpha", stagedFile.detectedStickerPack!!.suggestedName)
        assertEquals(2, stagedFile.detectedStickerPack!!.stickerCount)

        vm.importSharedStickerPack(stagedFile, "Meu Pacote Custom")
        val importDeadline = System.currentTimeMillis() + 5_000L
        while (vm.pendingIncomingShare.value != null && System.currentTimeMillis() < importDeadline) {
            delay(50L)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertNull(vm.pendingIncomingShare.value)
        assertFalse(stagedFile.file.exists())

        val packs = dao.observeStickerPacks().first()
        assertEquals(1, packs.size)
        val importedPack = packs.first()
        assertEquals("Meu Pacote Custom", importedPack.name)

        val stickers = dao.getPackStickersByPackId(importedPack.packId)
        assertEquals(2, stickers.size)
        val packDir = File(app.container.mediaStorage.stickerPacksDir, importedPack.packId)
        assertTrue(packDir.exists())

        suspend fun waitUntil(timeoutMs: Long = 3_000L, predicate: suspend () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                if (predicate()) return
                delay(40L)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Favorite sticker from pack
        vm.toggleFavoriteStickerFile(stickers.first().localPath, stickers.first().mimeType)
        waitUntil { dao.observeFavoriteStickers().first().isNotEmpty() }
        assertEquals(1, dao.observeFavoriteStickers().first().size)

        // Rename pack
        vm.renameStickerPack(importedPack.packId, "Pacote Renomeado")
        waitUntil { dao.getStickerPackById(importedPack.packId)?.name == "Pacote Renomeado" }
        assertEquals("Pacote Renomeado", dao.getStickerPackById(importedPack.packId)?.name)

        // Remove single sticker
        vm.removePackSticker(stickers.last().stickerId)
        waitUntil { dao.getPackStickersByPackId(importedPack.packId).size == 1 }
        assertEquals(1, dao.getPackStickersByPackId(importedPack.packId).size)

        // Delete pack removes DB rows + directory
        vm.deleteStickerPack(importedPack.packId)
        waitUntil { dao.observeStickerPacks().first().isEmpty() && !packDir.exists() }
        assertTrue(dao.observeStickerPacks().first().isEmpty())
        assertFalse(packDir.exists())

        shareEnc.delete()
    }

    @Test
    fun `stickers tray separates Todas Favoritas and pack tabs cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val f1 = File(context.cacheDir, "t_fav1.webp").apply { writeBytes(makeStaticWebpBytes(0x51)) }
        val f2 = File(context.cacheDir, "t_p1_2.webp").apply { writeBytes(makeAnimatedWebpBytes(0x52)) }
        val f3 = File(context.cacheDir, "t_p2_1.webp").apply { writeBytes(makeStaticWebpBytes(0x53)) }

        val favs = listOf(
            FavoriteStickerEntity("fav1", f1.absolutePath, "image/webp", "sha_shared", 10L)
        )
        val packs = listOf(
            StickerPackEntity("pack1", "nomedopacote1", "Author 1", f1.absolutePath, 100L),
            StickerPackEntity("pack2", "nomedopacote2", "Author 2", f3.absolutePath, 200L)
        )
        val pStickers = listOf(
            PackStickerEntity("ps1", "pack1", f1.absolutePath, "image/webp", "sha_shared", 0, 100L),
            PackStickerEntity("ps2", "pack1", f2.absolutePath, "image/webp", "sha_2", 1, 101L),
            PackStickerEntity("ps3", "pack2", f3.absolutePath, "image/webp", "sha_3", 0, 200L)
        )

        val sentFiles = mutableListOf<String>()

        composeTestRule.setContent {
            ChatsoTheme {
                StickersTrayContent(
                    favoriteStickers = favs,
                    onSendFavoriteSticker = {},
                    onRemoveFavoriteSticker = {},
                    stickerPacks = packs,
                    packStickers = pStickers,
                    onSendStickerFile = { path, _ -> sentFiles += path }
                )
            }
        }

        // Verify all 4 category pills are displayed: Todas, Favoritas, nomedopacote1, nomedopacote2
        composeTestRule.onNodeWithTag("stickers_tab_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stickers_tab_favorites").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stickers_tab_pack_pack1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stickers_tab_pack_pack2").assertIsDisplayed()

        // Todas merges and deduplicates shared sha256 -> fav1 + ps2 + ps3 (ps1 shares sha_shared with fav1)
        composeTestRule.onNodeWithTag("sticker_grid_item_fav_fav1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_grid_item_pack_ps2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_grid_item_pack_ps3").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("sticker_grid_item_pack_ps1").fetchSemanticsNodes().size)

        // Favoritas shows only fav1
        composeTestRule.onNodeWithTag("stickers_tab_favorites").performClick()
        composeTestRule.onNodeWithTag("sticker_grid_item_fav_fav1").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("sticker_grid_item_pack_ps2").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithTag("sticker_grid_item_pack_ps3").fetchSemanticsNodes().size)

        // nomedopacote1 shows ps1 and ps2 plus rename/delete controls
        composeTestRule.onNodeWithTag("stickers_tab_pack_pack1").performClick()
        composeTestRule.onNodeWithTag("sticker_pack_rename_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_pack_delete_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_grid_item_pack_ps1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_grid_item_pack_ps2").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("sticker_grid_item_pack_ps3").fetchSemanticsNodes().size)

        // nomedopacote2 shows ps3 and clicking sends it
        composeTestRule.onNodeWithTag("stickers_tab_pack_pack2").performClick()
        composeTestRule.onNodeWithTag("sticker_grid_item_pack_ps3").performClick()
        assertEquals(listOf(f3.absolutePath), sentFiles)

        f1.delete()
        f2.delete()
        f3.delete()
    }

    @Test
    fun `share target sheet and import dialog and chat bubble import buttons trigger pack import`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encFile = File(context.cacheDir, "chat_msg_pack.enc")
        buildZipArchive(
            encFile,
            listOf(
                "title.txt" to "Figurinhas Zap".toByteArray(),
                "1.webp" to makeAnimatedWebpBytes(0x61),
                "2.webp" to makeStaticWebpBytes(0x62)
            )
        )

        var importedFromShareName: String? = null
        var importedFromChatPath: String? = null
        var confirmedDialogName: String? = null

        val probe = StickerPackProbe(
            sourcePath = encFile.absolutePath,
            suggestedName = "Figurinhas Zap",
            author = "Tester",
            stickerCount = 2,
            previewStickers = listOf(makeAnimatedWebpBytes(0x61), makeStaticWebpBytes(0x62))
        )

        composeTestRule.setContent {
            ChatsoTheme {
                // 1) ChatMessageBubbleItem for received FILE .enc
                ChatMessageBubbleItem(
                    message = MessageEntity(
                        messageId = "msg_enc_1",
                        peerId = "peer_1",
                        isOutgoing = false,
                        type = MessageType.FILE.name,
                        fileName = "chat_msg_pack.enc",
                        mimeType = "application/octet-stream",
                        fileSizeBytes = encFile.length(),
                        mediaLocalPath = encFile.absolutePath,
                        transferProgress = 100,
                        status = DeliveryStatus.READ.name
                    ),
                    peerUsername = "Alice",
                    nowClockFlow = MutableStateFlow(System.currentTimeMillis()),
                    voicePlaybackState = VoicePlaybackState(),
                    onToggleVoice = { _, _ -> },
                    onOpenImageFullscreen = { _, _ -> },
                    onOpenVideoFullscreen = { _, _ -> },
                    onOpenAttachment = { _, _ -> },
                    onImportStickerPack = { path, _ -> importedFromChatPath = path },
                    onCancelTransfer = {},
                    onRetryDownload = {},
                    onSwipeReply = {},
                    onLongPressMessage = {},
                    onReactionChipClick = { _, _ -> },
                    onQuoteClick = {}
                )

                // 2) ImportStickerPackDialog
                ImportStickerPackDialog(
                    state = PendingPackImportUiState(
                        sourceFile = encFile,
                        displayNameHint = "chat_msg_pack.enc",
                        deleteSourceOnFinish = false,
                        probe = probe
                    ),
                    onConfirmImport = { confirmedDialogName = it },
                    onDismiss = {}
                )
            }
        }

        // Wait for produceState IO probe in ChatMessageBubbleItem
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("import_sticker_pack_btn_msg_enc_1").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("import_sticker_pack_btn_msg_enc_1").performClick()
        assertEquals(encFile.absolutePath, importedFromChatPath)

        // Confirm ImportStickerPackDialog edits name and invokes callback
        composeTestRule.onNodeWithTag("import_pack_name_input").performTextReplacement("Pack Editado")
        composeTestRule.onNodeWithTag("confirm_import_pack_button").performClick()
        assertEquals("Pack Editado", confirmedDialogName)

        // Test ShareTargetSheet pack import card separately
        val shareCompose = composeTestRule
        // Using a separate assertion on ViewModel or direct ShareTargetSheet in another test below
        assertNull(importedFromShareName)
        encFile.delete()
    }

    @Test
    fun `share target sheet displays detected sticker pack import card and invokes callback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encFile = File(context.cacheDir, "share_pack.enc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var importedPackName: String? = null

        val staged = StagedSharedFile(
            file = encFile,
            fileName = "share_pack.enc",
            mimeType = "application/octet-stream",
            sizeBytes = 1024L,
            messageType = MessageType.FILE,
            detectedStickerPack = StickerPackProbe(
                sourcePath = encFile.absolutePath,
                suggestedName = "Pack Compartilhado",
                author = "WhatsApp",
                stickerCount = 12,
                previewStickers = listOf(makeStaticWebpBytes(0x70))
            )
        )

        composeTestRule.setContent {
            ChatsoTheme {
                ShareTargetSheet(
                    share = PendingIncomingShare(
                        directTargetPeerId = null,
                        sharedText = null,
                        sharedFiles = listOf(staged)
                    ),
                    peers = listOf(
                        PeerEntity(peerId = "p1", publicKeyBase64 = "k", fingerprint = "f", username = "Alice")
                    ),
                    connectedPeerIds = setOf("p1"),
                    foregroundPeerIds = setOf("p1"),
                    onSend = { _, _ -> },
                    onImportStickerPack = { _, name -> importedPackName = name },
                    onGoToConnect = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("share_detected_sticker_pack_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_import_pack_name_input").performScrollTo().performTextReplacement("Meu Zap Pack")
        composeTestRule.onNodeWithTag("share_import_sticker_pack_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals("Meu Zap Pack", importedPackName)

        encFile.delete()
    }
}
