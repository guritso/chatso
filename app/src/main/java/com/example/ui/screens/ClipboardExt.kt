package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberClipboard(): ClipboardManager {
    val context = LocalContext.current
    return remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
}

internal fun ClipboardManager.copyText(label: String, text: String) {
    setPrimaryClip(ClipData.newPlainText(label, text))
}
