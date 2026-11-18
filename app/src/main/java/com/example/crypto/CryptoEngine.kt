package com.example.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Cryptography Engine for Chatso.
 * - Long-term account identity: ECDSA P-256 (secp256r1) generated locally, zero servers.
 * - Per-connection forward secrecy: Ephemeral ECDH P-256 signed by identity ECDSA key.
 * - Key derivation: RFC 5869 HKDF-SHA256 bound to full handshake transcript.
 * - Frame encryption: Authenticated AES-256-GCM with 96-bit monotonic nonces and AAD.
 */
object CryptoEngine {
    private const val EC_CURVE = "secp256r1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "chatso_identity_wrap_v1"
    private const val GCM_TAG_BITS = 128
    private val secureRandom = SecureRandom()

    data class IdentityKeyMaterial(
        val publicKeyBase64: String,
        val wrappedPrivateKeyBase64: String,
        val peerId: String,
        val fingerprint: String
    )

    fun generateIdentityKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        return kpg.generateKeyPair()
    }

    fun createIdentityMaterial(): IdentityKeyMaterial {
        val kp = generateIdentityKeyPair()
        val pubBase64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val wrappedPriv = wrapPrivateKey(kp.private.encoded)
        return IdentityKeyMaterial(
            publicKeyBase64 = pubBase64,
            wrappedPrivateKeyBase64 = wrappedPriv,
            peerId = computePeerId(kp.public.encoded),
            fingerprint = formatFingerprint(kp.public.encoded)
        )
    }

    fun decodePublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun unwrapPrivateKey(wrappedBase64: String): PrivateKey {
        val rawBytes = if (wrappedBase64.startsWith("KS1:")) {
            val payload = Base64.decode(wrappedBase64.removePrefix("KS1:"), Base64.NO_WRAP)
            val ivLen = payload[0].toInt() and 0xFF
            val iv = payload.copyOfRange(1, 1 + ivLen)
            val ciphertext = payload.copyOfRange(1 + ivLen, payload.size)
            val secretKey = getOrCreateKeystoreMasterKey()
                ?: throw IllegalStateException("Android Keystore master key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } else {
            Base64.decode(wrappedBase64.removePrefix("RAW:"), Base64.NO_WRAP)
        }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(rawBytes))
    }

    private fun wrapPrivateKey(pkcs8Bytes: ByteArray): String {
        return try {
            val masterKey = getOrCreateKeystoreMasterKey()
            if (masterKey != null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, masterKey)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(pkcs8Bytes)
                val out = ByteArray(1 + iv.size + encrypted.size)
                out[0] = iv.size.toByte()
                System.arraycopy(iv, 0, out, 1, iv.size)
                System.arraycopy(encrypted, 0, out, 1 + iv.size, encrypted.size)
                "KS1:" + Base64.encodeToString(out, Base64.NO_WRAP)
            } else {
                "RAW:" + Base64.encodeToString(pkcs8Bytes, Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            "RAW:" + Base64.encodeToString(pkcs8Bytes, Base64.NO_WRAP)
        }
    }

    private fun getOrCreateKeystoreMasterKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Exception) {
            null
        }
    }

    fun computePeerId(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun computePeerIdFromBase64(publicKeyBase64: String): String {
        val bytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        return computePeerId(bytes)
    }

    fun formatFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.take(16)
            .toList()
            .chunked(2)
            .joinToString(" · ") { pair ->
                pair.joinToString("") { "%02X".format(it) }
            }
    }

    fun formatFingerprintFromBase64(publicKeyBase64: String): String {
        return try {
            formatFingerprint(Base64.decode(publicKeyBase64, Base64.NO_WRAP))
        } catch (_: Exception) {
            publicKeyBase64.take(24)
        }
    }

    /**
     * Generates a deterministic 60-digit Safety Number (12 blocks of 5 digits)
     * identical on both devices so users can verify there is no MITM on the VPN/LAN.
     */
    fun computeSafetyNumber(localPubBase64: String, remotePubBase64: String): String {
        val sorted = listOf(localPubBase64.trim(), remotePubBase64.trim()).sorted()
        var hash = MessageDigest.getInstance("SHA-256").run {
            update("CHATSO-SAFETY-NUMBER-V1".toByteArray(Charsets.UTF_8))
            update(Base64.decode(sorted[0], Base64.NO_WRAP))
            update(Base64.decode(sorted[1], Base64.NO_WRAP))
            digest()
        }
        // Stretch via iterative SHA-256 rounds for 12 independent 5-digit blocks
        val blocks = mutableListOf<String>()
        repeat(12) { idx ->
            hash = MessageDigest.getInstance("SHA-256").run {
                update(hash)
                update(byteArrayOf(idx.toByte()))
                digest()
            }
            val num = ((hash[0].toLong() and 0xFF) shl 24) or
                ((hash[1].toLong() and 0xFF) shl 16) or
                ((hash[2].toLong() and 0xFF) shl 8) or
                (hash[3].toLong() and 0xFF)
            blocks.add("%05d".format(num % 100000L))
        }
        return blocks.chunked(4).joinToString("\n") { it.joinToString("  ") }
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    fun signHandshake(
        identityPrivateKey: PrivateKey,
        roleTag: String,
        identityPubBase64: String,
        ephemeralPubBase64: String,
        nonceBase64: String
    ): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(identityPrivateKey, secureRandom)
        sig.update("CHATSO-HS-V1|$roleTag".toByteArray(Charsets.UTF_8))
        sig.update(Base64.decode(identityPubBase64, Base64.NO_WRAP))
        sig.update(Base64.decode(ephemeralPubBase64, Base64.NO_WRAP))
        sig.update(Base64.decode(nonceBase64, Base64.NO_WRAP))
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    fun verifyHandshake(
        roleTag: String,
        identityPubBase64: String,
        ephemeralPubBase64: String,
        nonceBase64: String,
        signatureBase64: String
    ): Boolean {
        return try {
            val pubKey = decodePublicKey(identityPubBase64)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update("CHATSO-HS-V1|$roleTag".toByteArray(Charsets.UTF_8))
            sig.update(Base64.decode(identityPubBase64, Base64.NO_WRAP))
            sig.update(Base64.decode(ephemeralPubBase64, Base64.NO_WRAP))
            sig.update(Base64.decode(nonceBase64, Base64.NO_WRAP))
            sig.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        } catch (_: Exception) {
            false
        }
    }

    fun deriveSessionCipher(
        isInitiator: Boolean,
        localEphemeralPrivate: PrivateKey,
        remoteEphemeralPubBase64: String,
        clientIdentityPubBase64: String,
        clientEphPubBase64: String,
        clientNonceBase64: String,
        serverIdentityPubBase64: String,
        serverEphPubBase64: String,
        serverNonceBase64: String
    ): SessionCipher {
        val remoteEphPub = decodePublicKey(remoteEphemeralPubBase64)
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(localEphemeralPrivate)
        keyAgreement.doPhase(remoteEphPub, true)
        val sharedSecret = keyAgreement.generateSecret()

        val clientNonce = Base64.decode(clientNonceBase64, Base64.NO_WRAP)
        val serverNonce = Base64.decode(serverNonceBase64, Base64.NO_WRAP)
        val salt = clientNonce + serverNonce

        val transcriptHash = MessageDigest.getInstance("SHA-256").run {
            update(Base64.decode(clientIdentityPubBase64, Base64.NO_WRAP))
            update(Base64.decode(clientEphPubBase64, Base64.NO_WRAP))
            update(clientNonce)
            update(Base64.decode(serverIdentityPubBase64, Base64.NO_WRAP))
            update(Base64.decode(serverEphPubBase64, Base64.NO_WRAP))
            update(serverNonce)
            digest()
        }

        val prk = hkdfExtract(salt, sharedSecret)
        val c2sKey = hkdfExpand(prk, "CHATSO-C2S-KEY".toByteArray() + transcriptHash, 32)
        val s2cKey = hkdfExpand(prk, "CHATSO-S2C-KEY".toByteArray() + transcriptHash, 32)
        val c2sIv = hkdfExpand(prk, "CHATSO-C2S-IV".toByteArray() + transcriptHash, 4)
        val s2cIv = hkdfExpand(prk, "CHATSO-S2C-IV".toByteArray() + transcriptHash, 4)

        return if (isInitiator) {
            SessionCipher(
                txKey = SecretKeySpec(c2sKey, "AES"),
                rxKey = SecretKeySpec(s2cKey, "AES"),
                txIvPrefix = c2sIv,
                rxIvPrefix = s2cIv
            )
        } else {
            SessionCipher(
                txKey = SecretKeySpec(s2cKey, "AES"),
                rxKey = SecretKeySpec(c2sKey, "AES"),
                txIvPrefix = s2cIv,
                rxIvPrefix = c2sIv
            )
        }
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }
}

/**
 * Thread-safe full-duplex AES-256-GCM frame cipher with anti-replay monotonic sequence enforcement.
 * Wire payload inside encrypted frame: [8-byte seqNumber][AES-GCM Ciphertext + 16-byte Tag]
 */
class SessionCipher(
    private val txKey: SecretKey,
    private val rxKey: SecretKey,
    private val txIvPrefix: ByteArray,
    private val rxIvPrefix: ByteArray
) {
    private val txSeq = AtomicLong(1L)
    private var highestRxSeq = 0L
    private val txLock = Any()
    private val rxLock = Any()

    fun encrypt(frameKind: Byte, plaintext: ByteArray): ByteArray {
        synchronized(txLock) {
            val seq = txSeq.getAndIncrement()
            val iv = buildNonce(txIvPrefix, seq)
            val aad = buildAad(frameKind, seq)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, txKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            return ByteBuffer.allocate(8 + ciphertext.size)
                .putLong(seq)
                .put(ciphertext)
                .array()
        }
    }

    fun decrypt(frameKind: Byte, packet: ByteArray): ByteArray {
        synchronized(rxLock) {
            require(packet.size >= 8 + 16) { "Truncated encrypted packet" }
            val buf = ByteBuffer.wrap(packet)
            val seq = buf.long
            if (seq <= highestRxSeq) {
                throw SecurityException("Replayed or out-of-order packet detected (anti-replay)")
            }
            val ciphertext = ByteArray(packet.size - 8)
            buf.get(ciphertext)
            val iv = buildNonce(rxIvPrefix, seq)
            val aad = buildAad(frameKind, seq)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, rxKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(ciphertext)
            highestRxSeq = seq
            return plaintext
        }
    }

    private fun buildNonce(prefix4: ByteArray, seq: Long): ByteArray {
        return ByteBuffer.allocate(12)
            .put(prefix4, 0, 4)
            .putLong(seq)
            .array()
    }

    private fun buildAad(frameKind: Byte, seq: Long): ByteArray {
        return ByteBuffer.allocate(9)
            .put(frameKind)
            .putLong(seq)
            .array()
    }
}
