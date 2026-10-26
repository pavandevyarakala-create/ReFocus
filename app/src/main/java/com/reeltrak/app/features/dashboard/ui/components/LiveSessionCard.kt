package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.reeltrak.app.core.ui.theme.ReFocusColors

@Composable
fun LiveSessionCard(
    currentApp: String?,
    sessionStartTime: Long,
    reelsScrolled: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha = infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    var durationText by remember { mutableStateOf("0 min") }

    LaunchedEffect(sessionStartTime) {
        while (true) {
            val elapsedMs = System.currentTimeMillis() - sessionStartTime
            val minutes = (elapsedMs / 1000 / 60)
            val seconds = (elapsedMs / 1000) % 60
            durationText = if (minutes > 0) {
                "$minutes min"
            } else {
                "$seconds sec"
            }
            delay(1000)
        }
    }

    val appNameDisplay = currentApp?.replace("com.instagram.android", "Instagram")
        ?.replace("com.google.android.youtube", "YouTube Shorts")
        ?.replace("com.zhiliaoapp.musically", "TikTok")
        ?: "Unknown App"

    val sessionCardShape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = sessionCardShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            ),
        shape = sessionCardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(ReFocusColors.warning)
                    .graphicsLayer {
                        this.alpha = alpha.value
                    }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = ReFocusColors.warning,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Live Session",
                    style = MaterialTheme.typography.labelSmall,
                    color = ReFocusColors.textSecondary
                )
                Text(
                    text = "$appNameDisplay • $durationText",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$reelsScrolled Reels",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ReFocusColors.warning
            )
        }
    }
}
