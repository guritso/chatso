package com.example.network

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

data class QrInvitePayload(
    val ip: String,
    val port: Int,
    val publicKeyBase64: String,
    val username: String,
    val inviteToken: String,
    val instanceId: String = "",
    val irohNodeId: String = "",
    val irohRelayUrl: String = ""
)

object QrCodeHelper {

    fun buildInviteUri(
        ip: String,
        port: Int,
        publicKeyBase64: String,
        username: String,
        inviteToken: String,
        instanceId: String = "",
        irohNodeId: String = "",
        irohRelayUrl: String = ""
    ): String {
        val builder = Uri.Builder()
            .scheme("chatso")
            .authority("connect")
        if (ip.isNotBlank() && port in 1..65535) {
            builder.appendQueryParameter("ip", ip.trim())
            builder.appendQueryParameter("port", port.toString())
        }
        builder
            .appendQueryParameter("pub", publicKeyBase64.trim())
            .appendQueryParameter("name", username.trim())
            .appendQueryParameter("token", inviteToken.trim())
        if (instanceId.isNotBlank()) {
            builder.appendQueryParameter("inst", instanceId.trim())
        }
        if (irohNodeId.isNotBlank()) {
            builder.appendQueryParameter("iroh", irohNodeId.trim())
        }
        if (irohRelayUrl.isNotBlank()) {
            builder.appendQueryParameter("relay", irohRelayUrl.trim())
        }
        return builder.build().toString()
    }

    fun parseInviteUri(
        rawText: String,
        ticketParser: ((String) -> Pair<String, String>?)? = null
    ): QrInvitePayload? {
        return try {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("chatso://", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                val ip = uri.getQueryParameter("ip")?.trim().orEmpty()
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 0
                val iroh = uri.getQueryParameter("iroh")?.trim().orEmpty()
                val relay = uri.getQueryParameter("relay")?.trim()
                    ?.let { IrohRelayUrlUtils.normalizeSingleRelayUrl(it) ?: it }
                    .orEmpty()
                val hasTcp = ip.isNotBlank() && port in 1..65535
                if (!hasTcp && iroh.isBlank()) return null
                val pub = uri.getQueryParameter("pub") ?: ""
                val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                    ?: if (ip.isNotBlank()) ip else IrohRelayUrlUtils.shortEndpointId(iroh)
                val token = uri.getQueryParameter("token") ?: ""
                val inst = uri.getQueryParameter("inst") ?: ""
                QrInvitePayload(
                    ip = ip,
                    port = port,
                    publicKeyBase64 = pub,
                    username = name,
                    inviteToken = token,
                    instanceId = inst,
                    irohNodeId = iroh,
                    irohRelayUrl = relay
                )
            } else if (trimmed.startsWith("iroh://", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                val iroh = uri.host?.trim().orEmpty().ifBlank { uri.getQueryParameter("id")?.trim().orEmpty() }
                if (iroh.isBlank()) return null
                val relay = uri.getQueryParameter("relay")?.trim()
                    ?.let { IrohRelayUrlUtils.normalizeSingleRelayUrl(it) ?: it }
                    .orEmpty()
                val pub = uri.getQueryParameter("pub") ?: ""
                val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                    ?: IrohRelayUrlUtils.shortEndpointId(iroh)
                val token = uri.getQueryParameter("token") ?: ""
                val inst = uri.getQueryParameter("inst") ?: ""
                QrInvitePayload(
                    ip = "",
                    port = 0,
                    publicKeyBase64 = pub,
                    username = name,
                    inviteToken = token,
                    instanceId = inst,
                    irohNodeId = iroh,
                    irohRelayUrl = relay
                )
            } else {
                ticketParser?.invoke(trimmed)?.let { (id, relay) ->
                    return QrInvitePayload(
                        ip = "",
                        port = 0,
                        publicKeyBase64 = "",
                        username = IrohRelayUrlUtils.shortEndpointId(id),
                        inviteToken = "",
                        irohNodeId = id,
                        irohRelayUrl = relay
                    )
                }
                if (trimmed.contains("@")) {
                    val candidateId = trimmed.substringBefore("@").trim()
                    val candidateRelay = trimmed.substringAfter("@").trim()
                    if (IrohRelayUrlUtils.looksLikeIrohEndpointId(candidateId)) {
                        val normalizedRelay = IrohRelayUrlUtils.normalizeSingleRelayUrl(candidateRelay) ?: return null
                        return QrInvitePayload(
                            ip = "",
                            port = 0,
                            publicKeyBase64 = "",
                            username = IrohRelayUrlUtils.shortEndpointId(candidateId),
                            inviteToken = "",
                            irohNodeId = candidateId,
                            irohRelayUrl = normalizedRelay
                        )
                    }
                }
                if (IrohRelayUrlUtils.looksLikeIrohEndpointId(trimmed)) {
                    QrInvitePayload(
                        ip = "",
                        port = 0,
                        publicKeyBase64 = "",
                        username = IrohRelayUrlUtils.shortEndpointId(trimmed),
                        inviteToken = "",
                        irohNodeId = trimmed,
                        irohRelayUrl = ""
                    )
                } else if (trimmed.contains(":") && !trimmed.startsWith("http", ignoreCase = true)) {
                    // Also support plain ip:port strings
                    val host = trimmed.substringBeforeLast(":").trim()
                    val port = trimmed.substringAfterLast(":").trim().toIntOrNull() ?: return null
                    if (host.isBlank() || port !in 1..65535) return null
                    QrInvitePayload(ip = host, port = port, publicKeyBase64 = "", username = host, inviteToken = "")
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseIrohConnectInput(
        rawText: String,
        explicitRelayUrl: String = "",
        ticketParser: ((String) -> Pair<String, String>?)? = null
    ): QrInvitePayload? {
        val cleanRelay = explicitRelayUrl.trim()
        val normalizedExplicitRelay = if (cleanRelay.isNotEmpty()) {
            IrohRelayUrlUtils.normalizeSingleRelayUrl(cleanRelay) ?: return null
        } else {
            ""
        }
        val parsed = parseInviteUri(rawText, ticketParser)
        if (parsed != null && parsed.irohNodeId.isNotBlank()) {
            val resolvedRelay = normalizedExplicitRelay.ifBlank { parsed.irohRelayUrl }
            return parsed.copy(irohRelayUrl = resolvedRelay)
        }
        val trimmed = rawText.trim()
        // Fallback: allow valid endpoint IDs in non-standard base32/hex length if user pasted directly
        val noWhitespace = trimmed.none { it.isWhitespace() }
        if (noWhitespace && trimmed.length >= 32 && !trimmed.contains(":") && !trimmed.contains("/") && !trimmed.contains("@")) {
            return QrInvitePayload(
                ip = "",
                port = 0,
                publicKeyBase64 = "",
                username = IrohRelayUrlUtils.shortEndpointId(trimmed),
                inviteToken = "",
                irohNodeId = trimmed,
                irohRelayUrl = normalizedExplicitRelay
            )
        }
        return null
    }

    fun generateQrBitmap(content: String, sizePx: Int = 640): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            val darkColor = Color.rgb(15, 23, 42)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix.get(x, y)) darkColor else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeFromImageProxy(imageProxy: ImageProxy): String? {
        return try {
            val plane = imageProxy.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val width = imageProxy.width
            val height = imageProxy.height
            val source = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
            }
            reader.decodeWithState(binaryBitmap).text
        } catch (_: Exception) {
            null
        } finally {
            imageProxy.close()
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true
                    )
                )
            }
            reader.decodeWithState(binaryBitmap).text
        } catch (_: Exception) {
            null
        }
    }
}
