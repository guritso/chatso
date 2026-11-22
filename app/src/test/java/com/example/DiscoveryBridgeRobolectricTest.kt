package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoEngine
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.LocalIdentityStore
import com.example.data.MediaStorageHelper
import com.example.data.MessageType
import com.example.network.DiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiscoveryBridgeRobolectricTest {

    @Test
    fun `three node bridge mode relays discovery and e2ee chat between isolated peers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val discA = DiscoveryManager(context, scope)
        val discB = DiscoveryManager(context, scope)
        val discC = DiscoveryManager(context, scope)

        val matA = CryptoEngine.createIdentityMaterial()
        val matB = CryptoEngine.createIdentityMaterial()
        val matC = CryptoEngine.createIdentityMaterial()

        val cHandshakedWithAViaBridge = CompletableFuture<String>()
        val cReceivedTextFromA = CompletableFuture<String>()
        val aReceivedTextFromC = CompletableFuture<String>()
        val aDisconnectedFromC = CompletableFuture<Boolean>()

        fun makeListener(
            onHandshake: (peerId: String, viaBridgePeerId: String) -> Unit = { _, _ -> },
            onDisconnect: (peerId: String) -> Unit = {},
            onText: (peerId: String, text: String) -> Unit = { _, _ -> }
        ) = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {
                onHandshake(peerId, viaBridgePeerId)
            }
            override suspend fun onPeerDisconnected(peerId: String) {
                onDisconnect(peerId)
            }
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {
                onText(peerId, text)
            }
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("bridge_unused", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }

        val nodeA = P2PNodeManager(
            scope,
            discA,
            makeListener(
                onDisconnect = { peerId ->
                    if (peerId == matC.peerId) aDisconnectedFromC.complete(true)
                },
                onText = { peerId, text ->
                    if (peerId == matC.peerId) aReceivedTextFromC.complete(text)
                }
            )
        )
        val nodeB = P2PNodeManager(scope, discB, makeListener())
        val nodeC = P2PNodeManager(
            scope,
            discC,
            makeListener(
                onHandshake = { peerId, viaBridge ->
                    if (peerId == matA.peerId && viaBridge.isNotBlank()) {
                        cHandshakedWithAViaBridge.complete(viaBridge)
                    }
                },
                onText = { peerId, text ->
                    if (peerId == matA.peerId) cReceivedTextFromA.complete(text)
                }
            )
        )

        try {
            val idA = LocalIdentity(
                isInitialized = true, username = "Alice",
                publicKeyBase64 = matA.publicKeyBase64, wrappedPrivateKeyBase64 = matA.wrappedPrivateKeyBase64,
                peerId = matA.peerId, fingerprint = matA.fingerprint,
                listenPort = 43941, isDiscoverable = true, bridgeEnabled = false
            )
            val idB = LocalIdentity(
                isInitialized = true, username = "BridgeB",
                publicKeyBase64 = matB.publicKeyBase64, wrappedPrivateKeyBase64 = matB.wrappedPrivateKeyBase64,
                peerId = matB.peerId, fingerprint = matB.fingerprint,
                listenPort = 43942, isDiscoverable = true, bridgeEnabled = true
            )
            val idC = LocalIdentity(
                isInitialized = true, username = "Charlie",
                publicKeyBase64 = matC.publicKeyBase64, wrappedPrivateKeyBase64 = matC.wrappedPrivateKeyBase64,
                peerId = matC.peerId, fingerprint = matC.fingerprint,
                // Out of Alice's default loopback scan range so Alice discovers Charlie strictly through BridgeB.
                listenPort = 43943, isDiscoverable = true, bridgeEnabled = false
            )

            nodeA.updateIdentityAndEnsureServer(idA)
            nodeB.updateIdentityAndEnsureServer(idB)
            nodeC.updateIdentityAndEnsureServer(idC)
            delay(250)

            // Both Alice and Charlie connect directly to BridgeB (e.g. A on localhost, C on VPN)
            assertTrue(
                "Alice should connect directly to BridgeB",
                nodeA.connectToPeer("127.0.0.1", nodeB.boundPort.value, matB.publicKeyBase64).isSuccess
            )
            assertTrue(
                "Charlie should connect directly to BridgeB",
                nodeC.connectToPeer("127.0.0.1", nodeB.boundPort.value, matB.publicKeyBase64).isSuccess
            )

            val bridgeReadyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (nodeB.bridgeTelemetry.value.directSessionCount < 2 &&
                System.nanoTime() < bridgeReadyDeadline
            ) {
                delay(25)
            }
            assertEquals(
                "BridgeB should have both direct peers before advertising its directory",
                2,
                nodeB.bridgeTelemetry.value.directSessionCount
            )

            // When Alice probes BridgeB's beacon port, Alice discovers Charlie via BridgeB
            discA.applyIdentityPolicy(idA)
            discA.scanNetworkNow(listOf("127.0.0.1" to nodeB.boundPort.value))
            val discoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (discA.discoveredPeers.value[matC.peerId] == null &&
                System.nanoTime() < discoveryDeadline
            ) {
                delay(25)
            }
            val discoveredC = discA.discoveredPeers.value[matC.peerId]
            assertNotNull("Alice should discover Charlie through BridgeB", discoveredC)

            // Alice connects to Charlie via BridgeB and both exchange E2EE messages
            val bridgedId = nodeA.connectViaBridge(matB.peerId, matC.peerId, matC.publicKeyBase64).getOrThrow()
            assertEquals("Bridge handshake should identify Charlie", matC.peerId, bridgedId)
            assertEquals(
                "Charlie should report Alice's connection through BridgeB",
                matB.peerId,
                cHandshakedWithAViaBridge.get(3, TimeUnit.SECONDS)
            )

            assertTrue(
                "Alice should send a message through BridgeB",
                nodeA.sendTextMessage(
                    peerId = matC.peerId,
                    messageId = "bridge-msg-1",
                    text = "Hello Charlie across BridgeB!",
                    selfDestructSec = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            assertEquals(
                "Charlie should receive Alice's bridged message",
                "Hello Charlie across BridgeB!",
                cReceivedTextFromA.get(3, TimeUnit.SECONDS)
            )

            assertTrue(
                "Charlie should send a reply through BridgeB",
                nodeC.sendTextMessage(
                    peerId = matA.peerId,
                    messageId = "bridge-msg-2",
                    text = "Hi Alice back via BridgeB!",
                    selfDestructSec = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            assertEquals(
                "Alice should receive Charlie's bridged reply",
                "Hi Alice back via BridgeB!",
                aReceivedTextFromC.get(3, TimeUnit.SECONDS)
            )

            // BridgeB telemetry must reflect relayed frames, bytes, and routes between Alice & Charlie
            val bridgeStats = nodeB.bridgeTelemetry.value
            assertTrue("BridgeB telemetry should be enabled", bridgeStats.isEnabled)
            assertEquals("BridgeB should retain both direct sessions", 2, bridgeStats.directSessionCount)
            assertTrue("BridgeB should count relayed frames", bridgeStats.relayedFramesTotal >= 2L)
            assertTrue("BridgeB should count relayed bytes", bridgeStats.relayedBytesTotal > 0L)
            assertTrue("BridgeB should report an active route", bridgeStats.activeRoutes.isNotEmpty())

            // Stopping BridgeB cascades disconnect to Alice's bridged session with Charlie
            nodeB.disconnectAll()
            assertTrue(
                "Disconnecting BridgeB should close Alice's virtual session with Charlie",
                aDisconnectedFromC.get(3, TimeUnit.SECONDS)
            )
        } finally {
            nodeA.disconnectAll()
            nodeB.disconnectAll()
            nodeC.disconnectAll()
            discA.shutdown()
            discB.shutdown()
            discC.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `discovery scan clears stale offline peers from search results`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val discAlice = DiscoveryManager(context, scope)
        val discScanner = DiscoveryManager(context, scope)

        val aliceMat = CryptoEngine.createIdentityMaterial()
        val scannerMat = CryptoEngine.createIdentityMaterial()
        val listener = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {}
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_disc", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val nodeAlice = P2PNodeManager(scope, discAlice, listener)

        try {
            val aliceId = LocalIdentity(
                isInitialized = true, username = "Alice",
                publicKeyBase64 = aliceMat.publicKeyBase64, wrappedPrivateKeyBase64 = aliceMat.wrappedPrivateKeyBase64,
                peerId = aliceMat.peerId, fingerprint = aliceMat.fingerprint,
                listenPort = 43961, isDiscoverable = true
            )
            nodeAlice.updateIdentityAndEnsureServer(aliceId)
            repeat(30) {
                if (nodeAlice.serverRunning.value) return@repeat
                delay(50)
            }
            assertTrue(nodeAlice.serverRunning.value)

            discScanner.applyIdentityPolicy(
                LocalIdentity(
                    isInitialized = true, username = "Scanner",
                    publicKeyBase64 = scannerMat.publicKeyBase64, wrappedPrivateKeyBase64 = scannerMat.wrappedPrivateKeyBase64,
                    peerId = scannerMat.peerId, fingerprint = scannerMat.fingerprint,
                    listenPort = 43962, isDiscoverable = true
                )
            )
            discScanner.scanNetworkNow(listOf("127.0.0.1" to nodeAlice.boundPort.value))
            while (discScanner.isScanning.value) {
                delay(50)
            }
            assertTrue(discScanner.discoveredPeers.value.containsKey(aliceId.peerId))

            // Alice goes offline; scanning again with no target must clear Alice from discoveredPeers
            nodeAlice.disconnectAll()
            discAlice.shutdown()
            delay(100)

            discScanner.scanNetworkNow(knownTargets = emptyList())
            while (discScanner.isScanning.value) {
                delay(50)
            }
            assertFalse(discScanner.discoveredPeers.value.containsKey(aliceId.peerId))
        } finally {
            nodeAlice.disconnectAll()
            discAlice.shutdown()
            discScanner.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `dev loopback bridge test relays encrypted traffic and updates bridge telemetry`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val store = LocalIdentityStore(context)
        store.createIdentity("BridgeHost", null, true)

        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = store,
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )

        try {
            repeat(25) {
                if (repo.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            assertTrue(repo.nodeManager.serverRunning.value)

            val res = repo.devRunLoopbackBridgeTest()
            assertTrue("Expected bridge sandbox loopback test to succeed: ${res.exceptionOrNull()}", res.isSuccess)
            val telemetry = repo.bridgeTelemetry.value
            assertTrue(telemetry.relayedFramesTotal >= 1L)
            assertTrue(telemetry.activeRoutes.isNotEmpty())

            repo.devResetBridgeTelemetry()
            assertEquals(0L, repo.bridgeTelemetry.value.relayedFramesTotal)
            assertTrue(repo.bridgeTelemetry.value.activeRoutes.isEmpty())
        } finally {
            repo.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `discovery finds localhost peer on alternate port and classifies multi-network subnets`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val discPeer = DiscoveryManager(context, scope)
        val discScanner = DiscoveryManager(context, scope)
        val peerMat = CryptoEngine.createIdentityMaterial()
        val scannerMat = CryptoEngine.createIdentityMaterial()
        val listener = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {}
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_multi", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val nodePeer = P2PNodeManager(scope, discPeer, listener)

        try {
            val altPort = (LocalIdentity.DEFAULT_TCP_PORT + 1..LocalIdentity.DEFAULT_TCP_PORT + 10)
                .firstOrNull { candidate ->
                    runCatching { java.net.ServerSocket(candidate).use { true } }.getOrDefault(false)
                } ?: (LocalIdentity.DEFAULT_TCP_PORT + 5)

            nodePeer.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true,
                    username = "LocalhostAltPort",
                    publicKeyBase64 = peerMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = peerMat.wrappedPrivateKeyBase64,
                    peerId = peerMat.peerId,
                    fingerprint = peerMat.fingerprint,
                    listenPort = altPort,
                    isDiscoverable = true
                )
            )
            repeat(20) {
                if (nodePeer.serverRunning.value) return@repeat
                delay(25)
            }
            assertEquals(altPort, nodePeer.boundPort.value)

            discScanner.applyIdentityPolicy(
                LocalIdentity(
                    isInitialized = true,
                    username = "Scanner",
                    publicKeyBase64 = scannerMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = scannerMat.wrappedPrivateKeyBase64,
                    peerId = scannerMat.peerId,
                    fingerprint = scannerMat.fingerprint,
                    listenPort = 43971,
                    isDiscoverable = true
                )
            )

            // Scan WITHOUT passing knownTargets — should still discover LocalhostAltPort via loopback port range
            discScanner.scanNetworkNow(knownTargets = emptyList())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (discScanner.discoveredPeers.value[peerMat.peerId] == null && System.nanoTime() < deadline) {
                delay(25)
            }
            val found = discScanner.discoveredPeers.value[peerMat.peerId]
            assertNotNull("Expected scanner to auto-discover localhost peer on port $altPort", found)
            assertEquals(com.example.data.EndpointKind.LOCALHOST.badge, found!!.source)
            assertEquals(altPort, found.port)

            // Verify subnet & IP classification helpers across Localhost, LAN, and VPN
            val fakeTargets = listOf(
                com.example.network.NetworkScanTarget("lo", "127.0.0.1", com.example.data.EndpointKind.LOCALHOST, 8),
                com.example.network.NetworkScanTarget("wlan0", "192.168.10.20", com.example.data.EndpointKind.LAN, 24),
                com.example.network.NetworkScanTarget("tun0", "10.8.0.5", com.example.data.EndpointKind.VPN, 32)
            )
            assertEquals(com.example.data.EndpointKind.LOCALHOST, com.example.network.NetworkUtils.classifyIpKind("127.0.0.1", fakeTargets))
            assertEquals(com.example.data.EndpointKind.LAN, com.example.network.NetworkUtils.classifyIpKind("192.168.10.42", fakeTargets))
            assertEquals(com.example.data.EndpointKind.VPN, com.example.network.NetworkUtils.classifyIpKind("10.8.0.9", fakeTargets))
            assertEquals(com.example.data.EndpointKind.VPN, com.example.network.NetworkUtils.classifyIpKind("100.80.10.2", emptyList()))
        } finally {
            nodePeer.disconnectAll()
            discPeer.shutdown()
            discScanner.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `localhost discovery and direct chat succeed even when VPN is active in ConnectivityManager`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadowCm = org.robolectric.Shadows.shadowOf(cm)

        val wifiNet = org.robolectric.shadows.ShadowNetwork.newInstance(101)
        val vpnNet = org.robolectric.shadows.ShadowNetwork.newInstance(202)
        val wifiCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { caps ->
            val sc = org.robolectric.Shadows.shadowOf(caps)
            sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val vpnCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { caps ->
            val sc = org.robolectric.Shadows.shadowOf(caps)
            sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
            sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        val wifiInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            android.net.ConnectivityManager.TYPE_WIFI,
            0,
            true,
            android.net.NetworkInfo.State.CONNECTED
        )
        @Suppress("DEPRECATION")
        val vpnInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            android.net.ConnectivityManager.TYPE_VPN,
            0,
            true,
            android.net.NetworkInfo.State.CONNECTED
        )
        shadowCm.addNetwork(wifiNet, wifiInfo)
        shadowCm.addNetwork(vpnNet, vpnInfo)
        shadowCm.setNetworkCapabilities(wifiNet, wifiCaps)
        shadowCm.setNetworkCapabilities(vpnNet, vpnCaps)

        assertTrue(com.example.network.NetworkUtils.hasActiveVpn(context))
        val scanTargets = com.example.network.NetworkUtils.getActiveNetworkScanTargets(context)
        assertTrue(scanTargets.any { it.kind == com.example.data.EndpointKind.LOCALHOST })

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val discPeer = DiscoveryManager(context, scope)
        val discClient = DiscoveryManager(context, scope)
        val peerMat = CryptoEngine.createIdentityMaterial()
        val clientMat = CryptoEngine.createIdentityMaterial()
        val listener = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {}
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_vpn_lo", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val nodePeer = P2PNodeManager(scope, discPeer, listener)
        val nodeClient = P2PNodeManager(scope, discClient, listener)
        val occupiedDefault = runCatching {
            java.net.ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress("0.0.0.0", LocalIdentity.DEFAULT_TCP_PORT))
            }
        }.getOrNull()

        try {
            // Even if default 42850 is already occupied on the device, server picks sequential 42851..42860
            nodePeer.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true,
                    username = "VpnLocalPeer",
                    publicKeyBase64 = peerMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = peerMat.wrappedPrivateKeyBase64,
                    peerId = peerMat.peerId,
                    fingerprint = peerMat.fingerprint,
                    listenPort = LocalIdentity.DEFAULT_TCP_PORT,
                    isDiscoverable = true
                )
            )
            nodeClient.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true,
                    username = "VpnLocalClient",
                    publicKeyBase64 = clientMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = clientMat.wrappedPrivateKeyBase64,
                    peerId = clientMat.peerId,
                    fingerprint = clientMat.fingerprint,
                    listenPort = 43981,
                    isDiscoverable = true
                )
            )
            repeat(25) {
                if (nodePeer.serverRunning.value && nodeClient.serverRunning.value) return@repeat
                delay(25)
            }
            assertTrue(
                "Expected fallback port in 42850..42860, got ${nodePeer.boundPort.value}",
                nodePeer.boundPort.value in LocalIdentity.DEFAULT_TCP_PORT..(LocalIdentity.DEFAULT_TCP_PORT + 10)
            )

            discClient.scanNetworkNow(knownTargets = emptyList())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (discClient.discoveredPeers.value[peerMat.peerId] == null && System.nanoTime() < deadline) {
                delay(25)
            }
            val discovered = discClient.discoveredPeers.value[peerMat.peerId]
            assertNotNull("Localhost peer must still be discovered while VPN is active", discovered)
            assertEquals("127.0.0.1", discovered!!.ipAddress)
            assertEquals(com.example.data.EndpointKind.LOCALHOST.badge, discovered.source)

            val connectResult = nodeClient.connectToPeer(
                host = "localhost",
                port = discovered.port,
                expectedPubKeyBase64 = peerMat.publicKeyBase64
            )
            assertTrue("Expected localhost connect under active VPN to succeed: ${connectResult.exceptionOrNull()}", connectResult.isSuccess)
        } finally {
            runCatching { occupiedDefault?.close() }
            nodeClient.disconnectAll()
            nodePeer.disconnectAll()
            discClient.shutdown()
            discPeer.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `invisible peer stays hidden across bridge discovery and search never creates chats`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        dao.getAllPeersOnce().forEach {
            dao.deleteAllMessagesForPeer(it.peerId)
            dao.deletePeer(it.peerId)
        }

        val store = LocalIdentityStore(context)
        store.createIdentity("ScannerA", null, isDiscoverable = false)
        val repoA = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = store,
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )

        val discB = DiscoveryManager(context, scope)
        val discC = DiscoveryManager(context, scope)
        val matB = CryptoEngine.createIdentityMaterial()
        val matC = CryptoEngine.createIdentityMaterial()
        val noopListener = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {}
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_inv", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val nodeB = P2PNodeManager(scope, discB, noopListener)
        val nodeC = P2PNodeManager(scope, discC, noopListener)

        try {
            repeat(30) {
                if (repoA.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            nodeB.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true, username = "VisibleBridge",
                    publicKeyBase64 = matB.publicKeyBase64, wrappedPrivateKeyBase64 = matB.wrappedPrivateKeyBase64,
                    peerId = matB.peerId, fingerprint = matB.fingerprint,
                    listenPort = 43973, isDiscoverable = true, bridgeEnabled = true
                )
            )
            nodeC.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true, username = "InvisibleCharlie",
                    publicKeyBase64 = matC.publicKeyBase64, wrappedPrivateKeyBase64 = matC.wrappedPrivateKeyBase64,
                    peerId = matC.peerId, fingerprint = matC.fingerprint,
                    listenPort = 43974, isDiscoverable = false, bridgeEnabled = false
                )
            )
            repeat(30) {
                if (nodeB.serverRunning.value && nodeC.serverRunning.value) return@repeat
                delay(50)
            }

            // Connect C -> BridgeB while C is invisible
            assertTrue(nodeC.connectToPeer("127.0.0.1", nodeB.boundPort.value, matB.publicKeyBase64).isSuccess)
            delay(350)

            // BridgeB must NOT advertise InvisibleCharlie in bridgeTelemetry or beacons
            assertFalse(nodeB.bridgeTelemetry.value.advertisedPeerNames.contains("InvisibleCharlie"))

            // Even if ScannerA probes BridgeB directly and C's port directly, invisible C must NOT appear,
            // and scanning must NEVER auto-create any PeerEntity chats in Room
            val bPort = nodeB.boundPort.value
            val cPort = nodeC.boundPort.value
            repoA.discoveryManager.scanNetworkNow(
                knownTargets = listOf("127.0.0.1" to bPort, "127.0.0.1" to cPort)
            )
            while (repoA.discoveryManager.isScanning.value) {
                delay(50)
            }
            assertFalse(
                "Invisible peer C must not be discovered directly or via bridge",
                repoA.discoveryManager.discoveredPeers.value.containsKey(matC.peerId)
            )
            assertTrue(
                "Search/discovery must never auto-create chats in database",
                dao.getAllPeersOnce().isEmpty()
            )
        } finally {
            nodeC.disconnectAll()
            nodeB.disconnectAll()
            discC.shutdown()
            discB.shutdown()
            repoA.shutdown()
            dao.deleteAllMessagesForPeer(matB.peerId)
            dao.deleteAllMessagesForPeer(matC.peerId)
            dao.deletePeer(matB.peerId)
            dao.deletePeer(matC.peerId)
            scope.cancel()
        }
    }

    @Test
    fun `active saved chats auto-connect and bridge auto-connects saved target on demand`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        dao.getAllPeersOnce().forEach {
            dao.deleteAllMessagesForPeer(it.peerId)
            dao.deletePeer(it.peerId)
        }

        val storeB = LocalIdentityStore(context)
        storeB.createIdentity("BridgeRepo", null, isDiscoverable = true)
        storeB.setBridgeEnabled(true)
        val repoB = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = storeB,
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )

        val discA = DiscoveryManager(context, scope)
        val discC = DiscoveryManager(context, scope)
        val matA = CryptoEngine.createIdentityMaterial()
        val matC = CryptoEngine.createIdentityMaterial()
        val cReceivedFromA = CompletableFuture<String>()

        val listenerA = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {}
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_auto_a", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val listenerC = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {
                if (peerId == matA.peerId) cReceivedFromA.complete(text)
            }
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_auto_c", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val nodeA = P2PNodeManager(scope, discA, listenerA)
        val nodeC = P2PNodeManager(scope, discC, listenerC)

        try {
            repeat(30) {
                if (repoB.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            nodeA.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true, username = "Alice",
                    publicKeyBase64 = matA.publicKeyBase64, wrappedPrivateKeyBase64 = matA.wrappedPrivateKeyBase64,
                    peerId = matA.peerId, fingerprint = matA.fingerprint,
                    listenPort = 43985, isDiscoverable = false
                )
            )
            nodeC.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true, username = "SavedCharlie",
                    publicKeyBase64 = matC.publicKeyBase64, wrappedPrivateKeyBase64 = matC.wrappedPrivateKeyBase64,
                    peerId = matC.peerId, fingerprint = matC.fingerprint,
                    listenPort = 43986, isDiscoverable = false
                )
            )
            repeat(30) {
                if (nodeA.serverRunning.value && nodeC.serverRunning.value) return@repeat
                delay(50)
            }
            val bIdentity = storeB.getSnapshot()

            // Save Charlie as an existing active chat on BridgeRepo without manually connecting first
            dao.upsertPeer(
                com.example.data.PeerEntity(
                    peerId = matC.peerId,
                    username = "SavedCharlie",
                    publicKeyBase64 = matC.publicKeyBase64,
                    fingerprint = matC.fingerprint,
                    lastIp = "127.0.0.1",
                    lastPort = nodeC.boundPort.value
                )
            )
            assertFalse(repoB.nodeManager.connectedPeers.value.contains(matC.peerId))

            // Alice connects directly to BridgeRepo and initiates a bridged chat with Charlie;
            // BridgeRepo auto-connects to SavedCharlie on demand and delivers the message E2EE
            assertTrue(
                nodeA.connectToPeer("127.0.0.1", repoB.nodeManager.boundPort.value, bIdentity.publicKeyBase64).isSuccess
            )
            val bridgedRes = nodeA.connectViaBridge(bIdentity.peerId, matC.peerId, matC.publicKeyBase64)
            assertTrue(
                "Bridge should auto-connect to saved target Charlie without manual connection: ${bridgedRes.exceptionOrNull()}",
                bridgedRes.isSuccess
            )
            assertTrue(repoB.nodeManager.connectedPeers.value.contains(matC.peerId))
            assertTrue(
                nodeA.sendTextMessage(
                    peerId = matC.peerId,
                    messageId = "auto-bridge-msg-1",
                    text = "Hello via auto-connected bridge!",
                    selfDestructSec = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            assertEquals("Hello via auto-connected bridge!", cReceivedFromA.get(4, TimeUnit.SECONDS))

            // Also verify explicit autoConnectActiveChats() reconnects saved peers when disconnected
            repoB.nodeManager.disconnectAll()
            nodeA.disconnectAll()
            repoB.nodeManager.updateIdentityAndEnsureServer(bIdentity)
            repeat(30) {
                if (repoB.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            repoB.autoConnectActiveChats(force = true)
            val reconnectDeadline = System.currentTimeMillis() + 3000L
            while (
                !repoB.nodeManager.connectedPeers.value.contains(matC.peerId) &&
                System.currentTimeMillis() < reconnectDeadline
            ) {
                delay(50)
            }
            assertTrue(
                "autoConnectActiveChats should automatically reconnect saved peer Charlie",
                repoB.nodeManager.connectedPeers.value.contains(matC.peerId)
            )
        } finally {
            nodeA.disconnectAll()
            nodeC.disconnectAll()
            discA.shutdown()
            discC.shutdown()
            repoB.shutdown()
            dao.deleteAllMessagesForPeer(matA.peerId)
            dao.deleteAllMessagesForPeer(matC.peerId)
            dao.deletePeer(matA.peerId)
            dao.deletePeer(matC.peerId)
            scope.cancel()
        }
    }

    @Test
    fun `crossed bridge handshake succeeds and queued offline READ receipt syncs on reconnect`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val dbA = androidx.room.Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        val dbC = androidx.room.Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        val daoA = dbA.chatDao()
        val daoC = dbC.chatDao()

        val matB = CryptoEngine.createIdentityMaterial()

        val dirA = context.getDir("bridge_test_a", Context.MODE_PRIVATE)
        val dirC = context.getDir("bridge_test_c", Context.MODE_PRIVATE)
        val mockCtxA = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("bridge_a_$name", mode)
            override fun getFilesDir(): File = dirA
        }
        val mockCtxC = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("bridge_c_$name", mode)
            override fun getFilesDir(): File = dirC
        }

        val discB = DiscoveryManager(context, scope)
        val sessionsOnB = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val nodeB = P2PNodeManager(
            scope,
            discB,
            object : P2PEventListener {
                override suspend fun isKnownPeer(peerId: String) = true
                override suspend fun onPeerHandshaked(
                    peerId: String, publicKeyBase64: String, remoteIp: String,
                    remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
                ) {
                    sessionsOnB[peerId] = true
                }
                override suspend fun onPeerDisconnected(peerId: String) {
                    sessionsOnB.remove(peerId)
                }
                override suspend fun onIncomingTextMessage(
                    peerId: String, messageId: String, text: String, selfDestructSec: Int,
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
                ) {}
                override suspend fun onIncomingFileStart(
                    peerId: String, messageId: String, messageType: MessageType, fileName: String,
                    mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                    mediaWidth: Int, mediaHeight: Int
                ): File = File.createTempFile("unused_b", ".bin")
                override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
                override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
                override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
                override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
                override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
            }
        )

        var repoA: ChatRepository? = null
        var repoC: ChatRepository? = null
        try {
            nodeB.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true,
                    username = "RelayBob",
                    publicKeyBase64 = matB.publicKeyBase64,
                    wrappedPrivateKeyBase64 = matB.wrappedPrivateKeyBase64,
                    peerId = matB.peerId,
                    fingerprint = matB.fingerprint,
                    listenPort = 44102,
                    isDiscoverable = true,
                    bridgeEnabled = true,
                    instanceId = "inst_bob_relay_test"
                )
            )
            repeat(30) {
                if (nodeB.serverRunning.value) return@repeat
                delay(50)
            }
            val bPort = nodeB.boundPort.value

            val prefsFileA = File(dirA, "test_identity_a.preferences_pb").apply { delete() }
            val prefsFileC = File(dirC, "test_identity_c.preferences_pb").apply { delete() }
            val storeA = LocalIdentityStore(
                mockCtxA,
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { prefsFileA }
            )
            storeA.ensureInstanceId()
            storeA.createIdentity("AliceBridgeRepo", null, isDiscoverable = true)
            storeA.setListenPort(44103)
            repoA = ChatRepository(
                context = mockCtxA,
                scope = scope,
                chatDao = daoA,
                identityStore = storeA,
                mediaStorage = MediaStorageHelper(mockCtxA),
                isAppInForegroundProvider = { true }
            )
            val storeC = LocalIdentityStore(
                mockCtxC,
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { prefsFileC }
            )
            storeC.ensureInstanceId()
            storeC.createIdentity("CharlieBridgeRepo", null, isDiscoverable = true)
            storeC.setListenPort(44104)
            repoC = ChatRepository(
                context = mockCtxC,
                scope = scope,
                chatDao = daoC,
                identityStore = storeC,
                mediaStorage = MediaStorageHelper(mockCtxC),
                isAppInForegroundProvider = { true }
            )

            repeat(40) {
                if (repoA.nodeManager.serverRunning.value && repoC.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            val idA = storeA.getSnapshot()
            val idC = storeC.getSnapshot()
            assertTrue(idA.peerId != idC.peerId)
            assertTrue(idA.instanceId != idC.instanceId)

            assertTrue(repoA.connectDirect("127.0.0.1", bPort, matB.publicKeyBase64).isSuccess)
            assertTrue(repoC.connectDirect("127.0.0.1", bPort, matB.publicKeyBase64).isSuccess)
            repeat(40) {
                if (sessionsOnB.containsKey(idA.peerId) && sessionsOnB.containsKey(idC.peerId)) return@repeat
                delay(40)
            }
            assertTrue(sessionsOnB.containsKey(idA.peerId))
            assertTrue(sessionsOnB.containsKey(idC.peerId))

            // Simultaneous crossed connectViaBridge from both Alice and Charlie!
            val jobA = scope.async {
                repoA.connectViaBridge(matB.peerId, idC.peerId, idC.publicKeyBase64)
            }
            val jobC = scope.async {
                repoC.connectViaBridge(matB.peerId, idA.peerId, idA.publicKeyBase64)
            }
            val resA = jobA.await()
            val resC = jobC.await()
            assertTrue("Side A crossed bridge connect failed: ${resA.exceptionOrNull()}", resA.isSuccess)
            assertTrue("Side C crossed bridge connect failed: ${resC.exceptionOrNull()}", resC.isSuccess)

            // Charlie is backgrounded / not viewing chat with Alice -> receives message as DELIVERED (not yet READ)
            repoC.currentlyOpenPeerId = null
            assertTrue(repoA.sendText(idC.peerId, "Hello over crossed bridge!"))

            val deliveredDeadline = System.currentTimeMillis() + 3000L
            var cMsgId: String? = null
            while (System.currentTimeMillis() < deliveredDeadline) {
                val msgsC = daoC.getUnreadIncomingMessagesChronological(idA.peerId)
                if (msgsC.isNotEmpty()) {
                    cMsgId = msgsC.first().messageId
                    break
                }
                delay(40)
            }
            assertNotNull("Charlie should receive message over crossed bridge", cMsgId)

            val ackDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < ackDeadline) {
                if (daoA.getMessageById(cMsgId!!)?.status == DeliveryStatus.DELIVERED.name) break
                delay(40)
            }
            assertEquals(DeliveryStatus.DELIVERED.name, daoA.getMessageById(cMsgId!!)?.status)

            // Drop bridge connection on Charlie (tearing down virtual bridge session to Alice), then Charlie reads while offline!
            repoC.nodeManager.disconnectPeer(matB.peerId)
            delay(80)
            assertFalse(repoC.nodeManager.connectedPeers.value.contains(idA.peerId))
            repoC.markConversationRead(idA.peerId, fromNotification = true)
            assertEquals(DeliveryStatus.READ.name, daoC.getMessageById(cMsgId)?.status)

            // Alice's message is still DELIVERED while Charlie is disconnected
            assertEquals(DeliveryStatus.DELIVERED.name, daoA.getMessageById(cMsgId)?.status)

            // Charlie reconnects to bridge and peer -> queued READ receipt + RECEIPT_QUERY sync upgrades Alice to READ
            assertTrue(repoC.connectDirect("127.0.0.1", bPort, matB.publicKeyBase64).isSuccess)
            assertTrue(repoC.connectViaBridge(matB.peerId, idA.peerId, idA.publicKeyBase64).isSuccess)
            val readDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < readDeadline) {
                if (daoA.getMessageById(cMsgId)?.status == DeliveryStatus.READ.name) break
                delay(40)
            }
            assertEquals(
                "Alice's message must transition to READ after Charlie reconnects via bridge",
                DeliveryStatus.READ.name,
                daoA.getMessageById(cMsgId)?.status
            )
        } finally {
            repoA?.shutdown()
            repoC?.shutdown()
            nodeB.disconnectAll()
            discB.shutdown()
            scope.cancel()
            delay(100)
            dbA.close()
            dbC.close()
        }
    }
}
