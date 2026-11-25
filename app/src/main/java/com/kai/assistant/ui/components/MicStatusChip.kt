package com.kai.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
// Required: capital-M Modifier class (not the modifier package).
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class MicStatus {
    Off,
    Wake,
    /** Background listener is waiting for "Hey KAI". */
    Standby,
    Listening,
    Processing,
    Speaking,
    /** Call, or another app has the microphone. */
    Paused,
}

@Composable
fun MicStatusChip(
    status: MicStatus,
    modifier: Modifier = Modifier,
) {
    val (label, color, icon) = when (status) {
        MicStatus.Off -> Triple("Mic off", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.MicOff)
        MicStatus.Wake -> Triple("Hey KAI", Color(0xFF059669), Icons.Filled.Mic)
        MicStatus.Standby -> Triple("Hey KAI", Color(0xFF059669), Icons.Filled.Mic)
        MicStatus.Listening -> Triple("Listening", Color(0xFF059669), Icons.Filled.Mic)
        MicStatus.Processing -> Triple("Processing", Color(0xFFD97706), Icons.Filled.Mic)
        MicStatus.Speaking -> Triple("Speaking", Color(0xFF2563EB), Icons.AutoMirrored.Filled.VolumeUp)
        MicStatus.Paused -> Triple("Paused", Color(0xFFD97706), Icons.Filled.MicOff)
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
            contentDescription = if (status == MicStatus.Paused) "Paused (in call)" else label,
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
