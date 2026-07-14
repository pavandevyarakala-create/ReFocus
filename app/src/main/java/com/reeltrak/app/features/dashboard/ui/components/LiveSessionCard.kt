package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke

@Composable
fun LiveSessionCard(
    currentApp: String?,
    sessionStartTime: Long,
    reelsScrolled: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .alpha(alpha)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("Live Session", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.alpha(0.8f))
                Text("$appNameDisplay • $durationText", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("$reelsScrolled Reels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        }
    }
}
