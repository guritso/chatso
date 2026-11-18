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
    val inviteToken: String
)

object QrCodeHelper {

    fun buildInviteUri(
        ip: String,
        port: Int,
        publicKeyBase64: String,
        username: String,
        inviteToken: String
    ): String {
        return Uri.Builder()
            .scheme("chatso")
            .authority("connect")
            .appendQueryParameter("ip", ip.trim())
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("pub", publicKeyBase64.trim())
            .appendQueryParameter("name", username.trim())
            .appendQueryParameter("token", inviteToken.trim())
            .build()
            .toString()
    }

    fun parseInviteUri(rawText: String): QrInvitePayload? {
        return try {
            val trimmed = rawText.trim()
            if (trimmed.startsWith("chatso://")) {
                val uri = Uri.parse(trimmed)
                val ip = uri.getQueryParameter("ip")?.takeIf { it.isNotBlank() } ?: return null
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
                val pub = uri.getQueryParameter("pub") ?: ""
                val name = uri.getQueryParameter("name") ?: "Contato"
                val token = uri.getQueryParameter("token") ?: ""
                QrInvitePayload(ip = ip, port = port, publicKeyBase64 = pub, username = name, inviteToken = token)
            } else if (trimmed.contains(":")) {
                // Also support plain ip:port strings
                val host = trimmed.substringBeforeLast(":").trim()
                val port = trimmed.substringAfterLast(":").trim().toIntOrNull() ?: return null
                QrInvitePayload(ip = host, port = port, publicKeyBase64 = "", username = host, inviteToken = "")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
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
