package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.PeerPresence
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.WarningAmber
import java.io.File

@Composable
fun ChatsoAvatar(
    name: String,
    avatarModel: Any?,
    size: Dp = 48.dp,
    isOnline: Boolean = false,
    presence: PeerPresence = if (isOnline) PeerPresence.ONLINE else PeerPresence.OFFLINE,
    showPresenceDot: Boolean = false,
    modifier: Modifier = Modifier
) {
    val resolvedModel = when (avatarModel) {
        is String -> avatarModel.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        else -> avatarModel
    }
    val initials = name.trim()
        .split(" ", "_", "-")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "?" }

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (resolvedModel != null) {
                AsyncImage(
                    model = resolvedModel,
                    contentDescription = "Profile photo of $name",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (name.isNotBlank()) {
                Text(
                    text = initials,
                    style = if (size >= 56.dp) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Default avatar",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(size * 0.52f)
                )
            }
        }

        if (showPresenceDot) {
            val dotSize = (size * 0.28f).coerceIn(11.dp, 16.dp)
            val dotColor = when (presence) {
                PeerPresence.ONLINE -> OnlineEmerald
                PeerPresence.BACKGROUND -> WarningAmber
                PeerPresence.OFFLINE -> MaterialTheme.colorScheme.outline
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}
