package com.example

import android.util.Base64
import com.example.crypto.CryptoEngine
import com.example.data.ReactionCodec
import com.example.network.QrCodeHelper
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
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
class CryptoCodecRobolectricTest {

    @Test
    fun `ecdsa handshake and ecdh aes256gcm session cipher roundtrip`() {
        val aliceId = CryptoEngine.createIdentityMaterial()
        val bobId = CryptoEngine.createIdentityMaterial()

        assertEquals(
            CryptoEngine.computeSafetyNumber(aliceId.publicKeyBase64, bobId.publicKeyBase64),
            CryptoEngine.computeSafetyNumber(bobId.publicKeyBase64, aliceId.publicKeyBase64)
        )

        val aliceEph = CryptoEngine.generateIdentityKeyPair()
        val bobEph = CryptoEngine.generateIdentityKeyPair()
        val aliceEphB64 = Base64.encodeToString(aliceEph.public.encoded, Base64.NO_WRAP)
        val bobEphB64 = Base64.encodeToString(bobEph.public.encoded, Base64.NO_WRAP)
        val cNonce = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
        val sNonce = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)

        val alicePriv = CryptoEngine.unwrapPrivateKey(aliceId.wrappedPrivateKeyBase64)
        val sig = CryptoEngine.signHandshake(
            alicePriv,
            "CLIENT",
            aliceId.publicKeyBase64,
            aliceEphB64,
            cNonce
        )
        assertTrue(
            CryptoEngine.verifyHandshake(
                "CLIENT",
                aliceId.publicKeyBase64,
                aliceEphB64,
                cNonce,
                sig
            )
        )

        val aliceCipher = CryptoEngine.deriveSessionCipher(
            isInitiator = true,
            localEphemeralPrivate = aliceEph.private,
            remoteEphemeralPubBase64 = bobEphB64,
            clientIdentityPubBase64 = aliceId.publicKeyBase64,
            clientEphPubBase64 = aliceEphB64,
            clientNonceBase64 = cNonce,
            serverIdentityPubBase64 = bobId.publicKeyBase64,
            serverEphPubBase64 = bobEphB64,
            serverNonceBase64 = sNonce
        )
        val bobCipher = CryptoEngine.deriveSessionCipher(
            isInitiator = false,
            localEphemeralPrivate = bobEph.private,
            remoteEphemeralPubBase64 = aliceEphB64,
            clientIdentityPubBase64 = aliceId.publicKeyBase64,
            clientEphPubBase64 = aliceEphB64,
            clientNonceBase64 = cNonce,
            serverIdentityPubBase64 = bobId.publicKeyBase64,
            serverEphPubBase64 = bobEphB64,
            serverNonceBase64 = sNonce
        )

        val secretMsg = "Hello via encrypted VPN!".toByteArray(Charsets.UTF_8)
        val encrypted = aliceCipher.encrypt(0x10, secretMsg)
        val decrypted = bobCipher.decrypt(0x10, encrypted)
        assertArrayEquals(secretMsg, decrypted)
    }

    @Test
    fun `qr code generation and decoding roundtrip`() {
        val uri = QrCodeHelper.buildInviteUri("10.8.0.5", 42850, "PUBKEY123", "Marina", "TOK99")
        val bmp = QrCodeHelper.generateQrBitmap(uri, 320)
        assertNotNull(bmp)
        val decoded = QrCodeHelper.decodeFromBitmap(bmp!!)
        assertEquals(uri, decoded)
        val parsed = QrCodeHelper.parseInviteUri(decoded!!)
        assertNotNull(parsed)
        assertEquals("10.8.0.5", parsed!!.ip)
        assertEquals(42850, parsed.port)
        assertEquals("Marina", parsed.username)
        assertEquals("TOK99", parsed.inviteToken)
    }

    @Test
    fun `reaction codec handles toggle grouping and clearing`() {
        val (json1, wire1) = ReactionCodec.toggleMyReaction("", "❤️")
        assertEquals("❤️", wire1)
        val json2 = ReactionCodec.applyPeerReaction(json1, "❤️")
        val chipsSame = ReactionCodec.groupedChips(json2)
        assertEquals(1, chipsSame.size)
        assertEquals(2, chipsSame.first().count)
        assertTrue(chipsSame.first().byMe)

        val json3 = ReactionCodec.applyPeerReaction(json1, "😂")
        val chipsDiff = ReactionCodec.groupedChips(json3)
        assertEquals(2, chipsDiff.size)

        val (clearedMe, wireClear) = ReactionCodec.toggleMyReaction(json3, "❤️")
        assertEquals("", wireClear)
        val finalChips = ReactionCodec.groupedChips(clearedMe)
        assertEquals(1, finalChips.size)
        assertEquals("😂", finalChips.first().emoji)
        assertFalse(finalChips.first().byMe)
    }
}
