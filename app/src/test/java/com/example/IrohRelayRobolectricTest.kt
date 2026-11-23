package com.example

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.data.DeliveryStatus
import com.example.data.IrohPublicRelays
import com.example.data.IrohRelayMode
import com.example.data.IrohRelayState
import com.example.data.IrohRelayStatus
import com.example.data.LocalIdentity
import com.example.data.LocalIdentityStore
import com.example.data.MediaStorageHelper
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.network.IrohEndpointSession
import com.example.network.IrohPeerChannel
import com.example.network.IrohRelayUrlUtils
import com.example.network.IrohTransport
import com.example.network.IrohTransportConfig
import com.example.network.QrCodeHelper
import com.example.ui.screens.ConnectScreen
import com.example.ui.screens.ContactProfileScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ChatsoTheme
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class IrohRelayRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class InMemoryIrohMesh {
        val activeSessions = ConcurrentHashMap<String, FakeSession>()
        val startedConfigs = ConcurrentHashMap<String, IrohTransportConfig>()

        fun deriveEndpointId(secretKey: ByteArray?): String {
            val seed = secretKey ?: ByteArray(32)
            val digest = MessageDigest.getInstance("SHA-256").digest(seed)
            return digest.joinToString("") { "%02x".format(it) }.take(52)
        }

        fun createTransport(label: String): IrohTransport = object : IrohTransport {
            override suspend fun startEndpoint(config: IrohTransportConfig): IrohEndpointSession {
                val id = deriveEndpointId(config.secretKey)
                startedConfigs[label] = config
                val relay = when (config.mode) {
                    IrohRelayMode.PUBLIC -> config.relayUrls.firstOrNull() ?: "https://use1-1.relay.iroh.network./"
                    IrohRelayMode.PRIVATE -> config.relayUrls.firstOrNull()
                        ?: throw IllegalArgumentException("Missing private relay URL")
                    IrohRelayMode.DISABLED -> throw IllegalStateException("Disabled")
                }
                val session = FakeSession(this@InMemoryIrohMesh, id, relay)
                activeSessions[id]?.close()
                activeSessions[id] = session
                return session
            }

            override fun parseTicket(ticket: String): Pair<String, String>? {
                val clean = ticket.trim()
                if (!clean.startsWith("ticket:")) return null
                val parts = clean.removePrefix("ticket:").split("|", limit = 2)
                val id = parts.getOrNull(0)?.trim().orEmpty()
                if (id.isBlank()) return null
                val relay = parts.getOrNull(1)?.trim().orEmpty()
                return id to relay
            }
        }

        private class FakeChannel(
            override val remoteEndpointId: String,
            override val remoteRelayUrl: String,
            private val inbound: Channel<Pair<Byte, ByteArray>>,
            private val outbound: Channel<Pair<Byte, ByteArray>>,
            private val peerClosedFlag: () -> Boolean,
            private val closeHook: () -> Unit
        ) : IrohPeerChannel {
            @Volatile
            private var closed = false
            override val isClosed: Boolean
                get() = closed || peerClosedFlag()

            override suspend fun writeFrame(kind: Byte, payload: ByteArray) {
                if (isClosed) throw IOException("Channel closed")
                val res = outbound.trySend(kind to payload.copyOf())
                if (res.isFailure) {
                    outbound.send(kind to payload.copyOf())
                }
            }

            override suspend fun readFrame(maxFrameBytes: Int): Pair<Byte, ByteArray> {
                val frame = try {
                    inbound.receive()
                } catch (_: Throwable) {
                    throw EOFException("Iroh stream ended")
                }
                if (frame.second.size > maxFrameBytes) throw IOException("Frame too large")
                return frame
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    inbound.close()
                    outbound.close()
                    closeHook()
                }
            }
        }

        class FakeSession(
            private val mesh: InMemoryIrohMesh,
            override val endpointId: String,
            override val activeRelayUrl: String
        ) : IrohEndpointSession {
            @Volatile
            override var isClosed: Boolean = false
            val incomingChannels = Channel<IrohPeerChannel>(Channel.UNLIMITED)

            override suspend fun acceptPeerChannel(): IrohPeerChannel? {
                if (isClosed) return null
                return try {
                    incomingChannels.receive()
                } catch (_: Throwable) {
                    null
                }
            }

            override suspend fun connectPeerChannel(
                targetEndpointId: String,
                targetRelayUrl: String,
                directAddresses: List<String>
            ): IrohPeerChannel {
                if (isClosed) throw IOException("Local endpoint closed")
                val target = mesh.activeSessions[targetEndpointId]
                    ?.takeIf { !it.isClosed }
                    ?: throw IOException("Target endpoint $targetEndpointId unreachable on relay")
                if (targetRelayUrl.isNotBlank() && target.activeRelayUrl.isNotBlank() &&
                    targetRelayUrl.trimEnd('/') != target.activeRelayUrl.trimEnd('/')
                ) {
                    throw IOException("Relay mismatch: expected $targetRelayUrl got ${target.activeRelayUrl}")
                }
                val c2s = Channel<Pair<Byte, ByteArray>>(Channel.UNLIMITED)
                val s2c = Channel<Pair<Byte, ByteArray>>(Channel.UNLIMITED)
                var peerChannel: FakeChannel? = null
                val clientChannel = FakeChannel(
                    target.endpointId,
                    target.activeRelayUrl,
                    s2c,
                    c2s,
                    { isClosed || target.isClosed }
                ) {
                    peerChannel?.close()
                }
                peerChannel = FakeChannel(
                    this.endpointId,
                    this.activeRelayUrl,
                    c2s,
                    s2c,
                    { isClosed || target.isClosed }
                ) {
                    clientChannel.close()
                }
                target.incomingChannels.send(peerChannel)
                return clientChannel
            }

            override fun close() {
                if (!isClosed) {
                    isClosed = true
                    incomingChannels.close()
                    mesh.activeSessions.remove(endpointId, this)
                }
            }
        }
    }

    @Test
    fun `relay url utils validate public and private relay urls and endpoint ids`() {
        assertEquals(
            "https://relay.example.com:8443",
            IrohRelayUrlUtils.normalizeSingleRelayUrl("  https://relay.example.com:8443/  ")
        )
        assertEquals(
            "https://relay.example.com",
            IrohRelayUrlUtils.normalizeSingleRelayUrl("relay.example.com")
        )
        assertEquals(
            "http://192.168.1.5:3340",
            IrohRelayUrlUtils.normalizeSingleRelayUrl("http://192.168.1.5:3340/")
        )
        assertNull(IrohRelayUrlUtils.normalizeSingleRelayUrl("ftp://relay.example.com"))
        assertNull(IrohRelayUrlUtils.normalizeSingleRelayUrl(""))

        val list = IrohRelayUrlUtils.parseRelayUrlList("https://r1.example.com/, r2.example.com\nhttp://10.0.0.2:3340")
        assertEquals(
            listOf("https://r1.example.com", "https://r2.example.com", "http://10.0.0.2:3340"),
            list
        )
        assertNull(IrohRelayUrlUtils.parseRelayUrlList("ftp://bad.example.com"))
        assertEquals(emptyList<String>(), IrohRelayUrlUtils.parseRelayUrlList("   "))

        assertTrue(IrohRelayUrlUtils.looksLikeIrohEndpointId("a".repeat(52)))
        assertFalse(IrohRelayUrlUtils.looksLikeIrohEndpointId("192.168.1.10"))
    }

    @Test
    fun `local identity store persists public private and disabled iroh settings`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dsFile = File(context.filesDir, "iroh_store_test_${UUID.randomUUID()}.preferences_pb")
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
            val store = LocalIdentityStore(context, ds)
            val created = store.createIdentity("IrohTester", null, isDiscoverable = true)
            assertEquals(IrohRelayMode.PUBLIC, created.irohRelayMode)
            assertEquals("", created.irohPublicRelayUrl)
            assertTrue(created.irohSecretKeyBase64.isNotBlank())
            assertEquals(32, Base64.decode(created.irohSecretKeyBase64, Base64.NO_WRAP).size)

            val euServer = IrohPublicRelays.servers.first { it.id == "euc1" }.url
            store.setIrohPublicRelayUrl(euServer)
            store.setIrohPrivateRelayUrl("https://my-private-relay.example.com")
            store.setIrohRelayMode(IrohRelayMode.PRIVATE)
            store.setIrohEndpointIdCache("node1234567890")

            val updated = store.getSnapshot()
            assertEquals(IrohRelayMode.PRIVATE, updated.irohRelayMode)
            assertEquals(euServer, updated.irohPublicRelayUrl)
            assertEquals("https://my-private-relay.example.com", updated.irohPrivateRelayUrl)
            assertEquals("node1234567890", updated.irohEndpointId)

            store.setIrohRelayMode(IrohRelayMode.DISABLED)
            assertEquals(IrohRelayMode.DISABLED, store.getSnapshot().irohRelayMode)
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun `qr helper builds and parses dual stack and iroh only invite uris and tickets`() {
        val id52 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst"
        val dualUri = QrCodeHelper.buildInviteUri(
            ip = "192.168.1.40",
            port = 42850,
            publicKeyBase64 = "PUB123",
            username = "Alice",
            inviteToken = "TOK",
            instanceId = "INST",
            irohNodeId = id52,
            irohRelayUrl = "https://euc1-1.relay.iroh.network"
        )
        val parsedDual = QrCodeHelper.parseInviteUri(dualUri)
        assertNotNull(parsedDual)
        assertEquals("192.168.1.40", parsedDual!!.ip)
        assertEquals(42850, parsedDual.port)
        assertEquals(id52, parsedDual.irohNodeId)
        assertEquals("https://euc1-1.relay.iroh.network", parsedDual.irohRelayUrl)
        assertEquals("INST", parsedDual.instanceId)

        val irohOnlyUri = QrCodeHelper.buildInviteUri(
            ip = "",
            port = 0,
            publicKeyBase64 = "PUB999",
            username = "RelayOnly",
            inviteToken = "TOK2",
            irohNodeId = id52,
            irohRelayUrl = "https://priv.relay.example.com"
        )
        val parsedIrohOnly = QrCodeHelper.parseInviteUri(irohOnlyUri)
        assertNotNull(parsedIrohOnly)
        assertEquals("", parsedIrohOnly!!.ip)
        assertEquals(0, parsedIrohOnly.port)
        assertEquals(id52, parsedIrohOnly.irohNodeId)
        assertEquals("https://priv.relay.example.com", parsedIrohOnly.irohRelayUrl)

        val parsedScheme = QrCodeHelper.parseIrohConnectInput("iroh://$id52?relay=https://priv.relay.example.com")
        assertNotNull(parsedScheme)
        assertEquals(id52, parsedScheme!!.irohNodeId)
        assertEquals("https://priv.relay.example.com", parsedScheme.irohRelayUrl)

        val parsedAt = QrCodeHelper.parseIrohConnectInput("$id52@priv.relay.example.com")
        assertNotNull(parsedAt)
        assertEquals(id52, parsedAt!!.irohNodeId)
        assertEquals("https://priv.relay.example.com", parsedAt.irohRelayUrl)

        val parsedTicket = QrCodeHelper.parseIrohConnectInput(
            rawText = "ticket:$id52|https://ticket.relay.example.com",
            ticketParser = { raw ->
                val parts = raw.removePrefix("ticket:").split("|")
                parts[0] to parts[1]
            }
        )
        assertNotNull(parsedTicket)
        assertEquals(id52, parsedTicket!!.irohNodeId)
        assertEquals("https://ticket.relay.example.com", parsedTicket.irohRelayUrl)
    }

    @Test
    fun `two repositories exchange encrypted messages and receipts over public and private iroh relays without tcp`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mesh = InMemoryIrohMesh()
        val dsFileA = File(context.filesDir, "iroh_peer_a_${UUID.randomUUID()}.preferences_pb")
        val dsFileB = File(context.filesDir, "iroh_peer_b_${UUID.randomUUID()}.preferences_pb")
        val dbA = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()

        val dsA = PreferenceDataStoreFactory.create(scope = scopeA) { dsFileA }
        val dsB = PreferenceDataStoreFactory.create(scope = scopeB) { dsFileB }
        val storeA = LocalIdentityStore(context, dsA)
        val storeB = LocalIdentityStore(context, dsB)
        val mediaStorage = MediaStorageHelper(context)

        val repoA = ChatRepository(
            context = context,
            scope = scopeA,
            chatDao = dbA.chatDao(),
            identityStore = storeA,
            mediaStorage = mediaStorage,
            isAppInForegroundProvider = { true },
            irohTransport = mesh.createTransport("A")
        )
        val repoB = ChatRepository(
            context = context,
            scope = scopeB,
            chatDao = dbB.chatDao(),
            identityStore = storeB,
            mediaStorage = mediaStorage,
            isAppInForegroundProvider = { true },
            irohTransport = mesh.createTransport("B")
        )

        try {
            val idA = storeA.createIdentity("AliceIroh", null, isDiscoverable = true)
            val idB = storeB.createIdentity("BobIroh", null, isDiscoverable = true)
            val euPublicUrl = IrohPublicRelays.servers.first { it.id == "euc1" }.url
            storeA.setIrohPublicRelayUrl(euPublicUrl)
            storeB.setIrohPublicRelayUrl(euPublicUrl)

            withTimeout(6_000L) {
                repoA.irohStatus.first { it.state == IrohRelayState.ONLINE && it.endpointId.isNotBlank() }
                repoB.irohStatus.first { it.state == IrohRelayState.ONLINE && it.endpointId.isNotBlank() }
            }
            val bobStatus = repoB.irohStatus.value
            assertEquals(IrohRelayMode.PUBLIC, bobStatus.mode)
            assertEquals(euPublicUrl, bobStatus.activeRelayUrl)

            // Connect A -> B purely via Iroh (no LAN/VPN TCP connection at all)
            val connectRes = repoA.connectViaIroh(
                targetEndpointId = bobStatus.endpointId,
                targetRelayUrl = bobStatus.activeRelayUrl
            )
            assertTrue("Expected Iroh connect to succeed: ${connectRes.exceptionOrNull()}", connectRes.isSuccess)
            assertEquals(idB.peerId, connectRes.getOrThrow())

            withTimeout(5_000L) {
                while (dbA.chatDao().getPeerById(idB.peerId)?.username != "BobIroh" ||
                    dbB.chatDao().getPeerById(idA.peerId)?.username != "AliceIroh"
                ) {
                    delay(50L)
                }
            }

            val savedBobOnA = dbA.chatDao().getPeerById(idB.peerId)!!
            assertEquals(bobStatus.endpointId, savedBobOnA.irohNodeId)
            assertEquals(euPublicUrl, savedBobOnA.irohRelayUrl)
            assertEquals("", savedBobOnA.lastIp)

            // Send encrypted text from A to B over Iroh and verify delivery + read receipt propagation
            repoB.currentlyOpenPeerId = idA.peerId
            repoB.isOpenChatAtBottom = true
            val sentOk = repoA.sendText(idB.peerId, "Hello via Public Iroh Relay!")
            assertTrue(sentOk)

            withTimeout(5_000L) {
                while (dbB.chatDao().observeMessagesForPeer(idA.peerId, 50).first()
                        .none { it.textContent == "Hello via Public Iroh Relay!" }
                ) {
                    delay(50L)
                }
                while (dbA.chatDao().observeMessagesForPeer(idB.peerId, 50).first()
                        .firstOrNull { it.textContent == "Hello via Public Iroh Relay!" }
                        ?.status != DeliveryStatus.READ.name
                ) {
                    delay(50L)
                }
            }

            // Switch both peers to a Private custom relay URL & verify automatic reconnect + messaging
            val privateRelay = "https://private-relay.mycorp.internal:3340"
            storeA.setIrohPrivateRelayUrl(privateRelay)
            storeA.setIrohRelayMode(IrohRelayMode.PRIVATE)
            storeB.setIrohPrivateRelayUrl(privateRelay)
            storeB.setIrohRelayMode(IrohRelayMode.PRIVATE)

            withTimeout(6_000L) {
                repoA.irohStatus.first { it.state == IrohRelayState.ONLINE && it.activeRelayUrl == privateRelay }
                repoB.irohStatus.first { it.state == IrohRelayState.ONLINE && it.activeRelayUrl == privateRelay }
            }
            assertEquals(listOf(privateRelay), mesh.startedConfigs["A"]?.relayUrls)
            assertEquals(listOf(privateRelay), mesh.startedConfigs["B"]?.relayUrls)

            // Simulate link drop and confirm ensurePeerConnected automatically falls back to Iroh
            repoA.nodeManager.disconnectPeer(idB.peerId)
            dbA.chatDao().upsertPeer(savedBobOnA.copy(lastIp = "", irohRelayUrl = privateRelay))
            withTimeout(5_000L) {
                while (repoA.nodeManager.connectedPeers.value.contains(idB.peerId)) {
                    delay(40L)
                }
            }
            assertTrue(repoA.ensurePeerConnected(idB.peerId))
            assertTrue(repoB.sendText(idA.peerId, "Private Relay Reply!"))
            withTimeout(5_000L) {
                while (dbA.chatDao().observeMessagesForPeer(idB.peerId, 50).first()
                        .none { it.textContent == "Private Relay Reply!" }
                ) {
                    delay(50L)
                }
            }

            // Disabling Iroh turns off the endpoint and blocks outbound Iroh connections
            storeA.setIrohRelayMode(IrohRelayMode.DISABLED)
            withTimeout(5_000L) {
                repoA.irohStatus.first { it.state == IrohRelayState.DISABLED }
            }
            assertTrue(repoA.connectViaIroh(bobStatus.endpointId, privateRelay).isFailure)
        } finally {
            repoA.shutdown()
            repoB.shutdown()
            scopeA.cancel()
            scopeB.cancel()
            dbA.close()
            dbB.close()
            dsFileA.delete()
            dsFileB.delete()
        }
    }

    @Test
    fun `settings screen allows switching public private and off iroh relay modes`() {
        val identityState = mutableStateOf(
            LocalIdentity(
                isInitialized = true,
                username = "Tester",
                peerId = "peer_local",
                irohRelayMode = IrohRelayMode.PUBLIC,
                irohEndpointId = "endpoint1234567890"
            )
        )
        var savedPrivateUrl = ""
        var reconnectClicks = 0

        composeTestRule.setContent {
            ChatsoTheme {
                SettingsScreen(
                    identity = identityState.value,
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
                    onOpenDevTools = {},
                    irohStatus = IrohRelayStatus(
                        mode = identityState.value.irohRelayMode,
                        state = if (identityState.value.irohRelayMode == IrohRelayMode.DISABLED) {
                            IrohRelayState.DISABLED
                        } else {
                            IrohRelayState.ONLINE
                        },
                        endpointId = "endpoint1234567890",
                        activeRelayUrl = if (identityState.value.irohRelayMode == IrohRelayMode.PRIVATE) {
                            identityState.value.irohPrivateRelayUrl
                        } else {
                            identityState.value.irohPublicRelayUrl
                        }
                    ),
                    onChangeIrohMode = { mode ->
                        identityState.value = identityState.value.copy(irohRelayMode = mode)
                    },
                    onSelectIrohPublicServer = { url ->
                        identityState.value = identityState.value.copy(irohPublicRelayUrl = url)
                    },
                    onSaveIrohPrivateRelay = { url ->
                        savedPrivateUrl = url
                        identityState.value = identityState.value.copy(irohPrivateRelayUrl = url)
                    },
                    onReconnectIroh = { reconnectClicks++ }
                )
            }
        }

        // Network section starts collapsed
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("settings_iroh_mode_public")).fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithTag("settings_network_expand_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_iroh_mode_public").performScrollTo().assertIsDisplayed()

        // Select EU Central public relay server
        composeTestRule.onNodeWithTag("settings_iroh_public_euc1").performScrollTo().performClick()
        assertEquals(
            IrohPublicRelays.servers.first { it.id == "euc1" }.url,
            identityState.value.irohPublicRelayUrl
        )

        // Switch to Private mode, type URL, and click Apply
        composeTestRule.onNodeWithTag("settings_iroh_mode_private").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_iroh_private_input").performScrollTo().apply {
            performTextClearance()
            performTextInput("https://my-iroh.example.com")
        }
        composeTestRule.onNodeWithTag("settings_iroh_private_apply_button").performScrollTo().performClick()
        assertEquals("https://my-iroh.example.com", savedPrivateUrl)

        // Trigger reconnect
        composeTestRule.onNodeWithTag("settings_iroh_reconnect_button").performScrollTo().performClick()
        assertEquals(1, reconnectClicks)

        // Turn Off Iroh relay
        composeTestRule.onNodeWithTag("settings_iroh_mode_off").performScrollTo().performClick()
        assertEquals(IrohRelayMode.DISABLED, identityState.value.irohRelayMode)
    }

    @Test
    fun `connect screen iroh tab and contact profile show iroh details and trigger connect`() {
        var submittedPeer = ""
        var submittedRelay = ""
        val targetId = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst"

        composeTestRule.setContent {
            ChatsoTheme {
                ConnectScreen(
                    identity = LocalIdentity(
                        isInitialized = true,
                        username = "Alice",
                        irohRelayMode = IrohRelayMode.PUBLIC,
                        irohEndpointId = "localirohendpoint12345"
                    ),
                    endpoints = emptyList(),
                    boundPort = 42850,
                    discoveredPeers = emptyList(),
                    isScanning = false,
                    isConnecting = false,
                    irohStatus = IrohRelayStatus(
                        mode = IrohRelayMode.PUBLIC,
                        state = IrohRelayState.ONLINE,
                        endpointId = "localirohendpoint12345",
                        activeRelayUrl = "https://use1-1.relay.iroh.network"
                    ),
                    onConnectManualIp = { _, _ -> },
                    onConnectIroh = { input, relay ->
                        submittedPeer = input
                        submittedRelay = relay
                    },
                    onConnectDiscoveredPeer = {},
                    onConnectQrRaw = {},
                    onDecodeQrFromImage = {},
                    onScanNetwork = {},
                    onRotateQrToken = {},
                    onToggleDiscoverable = {},
                    onNotify = {}
                )
            }
        }

        // Open Iroh tab (index 2)
        composeTestRule.onNodeWithTag("connect_mode_2").performClick()
        composeTestRule.onNodeWithTag("connect_iroh_status_badge").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_iroh_local_id").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("connect_iroh_input").performScrollTo().performTextInput(targetId)
        composeTestRule.onNodeWithTag("connect_iroh_relay_input").performScrollTo()
            .performTextInput("https://custom-relay.example.com")
        composeTestRule.onNodeWithTag("connect_iroh_button").performScrollTo().performClick()

        assertEquals(targetId, submittedPeer)
        assertEquals("https://custom-relay.example.com", submittedRelay)
    }

    @Test
    fun `contact profile displays iroh badge and endpoint id when peer uses iroh`() {
        val peer = PeerEntity(
            peerId = "peer_iroh_profile",
            username = "RelayBob",
            publicKeyBase64 = "PUB",
            fingerprint = "AA:BB:CC:DD",
            lastIp = "",
            lastPort = 42850,
            irohNodeId = "bobirohendpoint987654",
            irohRelayUrl = "https://euc1-1.relay.iroh.network"
        )
        composeTestRule.setContent {
            ChatsoTheme {
                ContactProfileScreen(
                    peer = peer,
                    presence = PeerPresence.ONLINE,
                    selfDestructLabel = "Off",
                    onBack = {},
                    onViewAvatar = {},
                    onOpenSafetyNumber = {},
                    onNotify = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("contact_profile_iroh_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("contact_profile_iroh_row").performScrollTo().assertIsDisplayed()
    }
}
