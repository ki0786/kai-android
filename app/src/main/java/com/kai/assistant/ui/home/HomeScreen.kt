package com.kai.assistant.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
// Required: capital-M Modifier class (not the modifier package).
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kai.assistant.R
import com.kai.assistant.ui.components.MicStatus
import com.kai.assistant.ui.components.MicStatusChip
import com.kai.assistant.ui.components.OnDeviceStatusBanner
import kotlin.math.sin

@Composable
fun HomeScreen(
    onStartVoice: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.18f),
                        colors.background,
                        colors.secondary.copy(alpha = 0.12f),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                MicStatusChip(
                    status = MicStatus.Off,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.35f))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge,
                color = colors.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(36.dp))
            AnimatedOrbPlaceholder()
            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.privacy_no_background),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.weight(0.45f))

            OnDeviceStatusBanner()
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onStartVoice,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.cta_voice))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.cta_chat))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AnimatedOrbPlaceholder() {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.size(220.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension * 0.28f * breathe

        // Soft ambient wash
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = base * 2.4f,
            ),
            radius = base * 2.4f,
            center = center,
        )

        // Expanding rings
        for (i in 0..2) {
            val t = ((pulse + i / 3f) % 1f)
            val radius = base + t * base * 1.6f
            val alpha = (1f - t) * 0.45f
            drawCircle(
                color = secondary.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )
        }

        // Core orb
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.95f), primary, secondary),
                center = center + Offset(0f, -base * 0.15f),
                radius = base,
            ),
            radius = base,
            center = center,
        )

        // Mic glyph hint
        val micW = base * 0.28f
        val micH = base * 0.42f
        val wobble = sin(pulse * Math.PI * 2).toFloat() * 2.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(center.x - micW / 2f, center.y - micH / 2f + wobble),
            size = androidx.compose.ui.geometry.Size(micW, micH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(micW / 2f, micW / 2f),
        )
    }
}
