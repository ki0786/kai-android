package com.kai.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier.modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class MicStatus {
    Off,
    Listening,
    Processing,
    Speaking,
}

@Composable
fun MicStatusChip(
    status: MicStatus,
    modifier: Modifier = Modifier,
) {
    val (label, color, icon) = when (status) {
        MicStatus.Off -> Triple("Mic off", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.MicOff)
        MicStatus.Listening -> Triple("Listening", Color(0xFF059669), Icons.Filled.Mic)
        MicStatus.Processing -> Triple("Processing", Color(0xFFD97706), Icons.Filled.Mic)
        MicStatus.Speaking -> Triple("Speaking", Color(0xFF2563EB), Icons.Filled.VolumeUp)
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
