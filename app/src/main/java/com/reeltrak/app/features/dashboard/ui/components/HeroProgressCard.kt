package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke

@Composable
fun HeroProgressCard(dailyIntentProvider: () -> Int, currentReelsProvider: () -> Int) {
    val dailyIntent = dailyIntentProvider()
    val currentReels = currentReelsProvider()
    val progress = if (dailyIntent > 0) currentReels.toFloat() / dailyIntent else 0f
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        val isNotSet = dailyIntent == -1
        val isNoLimit = dailyIntent == 0

        Row(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Today's Progress", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("$currentReels", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                
                if (isNotSet) {
                    Text("Reels", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "⚠️ Limit not set today", 
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), 
                            color = MaterialTheme.colorScheme.onErrorContainer, 
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else if (isNoLimit) {
                    Text("Reels (No Limit)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("out of $dailyIntent reels", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = if (progress >= 1f) "Limit reached" else "You're on track!",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), 
                            color = MaterialTheme.colorScheme.onPrimaryContainer, 
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            if (!isNotSet && !isNoLimit) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 12.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    )
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 12.dp,
                    color = MaterialTheme.colorScheme.primary
                )

                // Use normal Text composable with aggressive responsive font size
                val percentageString = "${(progress * 100).toInt()}%"
                
                // We want the text to occupy ~70% of the inner circle.
                // Circle size is 120.dp, stroke is 12.dp. Inner diameter is ~96.dp.
                // Standard 32.sp is large and premium for 2-3 chars.
                val fontSize = when {
                    percentageString.length <= 3 -> 32.sp // e.g., "75%" (Large and bold)
                    percentageString.length == 4 -> 24.sp // e.g., "125%" (Slight reduction)
                    else -> 20.sp // e.g., "1245%" (Only if extremely high)
                }
                
                Text(
                    text = percentageString,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fontSize,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = LocalTextStyle.current.copy(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                            includeFontPadding = false
                        )
                    )
                )
            }
        } else {
            Spacer(modifier = Modifier.size(120.dp))
        }
    }
}
}
