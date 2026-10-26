package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reeltrak.app.core.ui.theme.ReFocusColors

/**
 * Concise, polished informational dialog displayed when a non-Premium / non-Trial user
 * taps the locked Strict Mode card on the Dashboard.
 *
 * Requirements:
 * - Communicates feature value concisely.
 * - Shows clear "PREMIUM FEATURE" indicator with lock icon.
 * - Provides primary CTA to unlock with Premium (opens Subscription paywall).
 * - Provides secondary button to dismiss.
 * - Tapping does NOT activate Strict Mode under any circumstances.
 */
@Composable
fun StrictModeInfoDialog(
    onDismissRequest: () -> Unit,
    onGetPremiumClick: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ReFocusColors.surface,
            border = BorderStroke(1.dp, ReFocusColors.border),
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Lock Icon Container
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(ReFocusColors.accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Strict Mode Locked",
                        tint = ReFocusColors.accent,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Feature Title
                Text(
                    text = "Strict Mode",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = ReFocusColors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Premium Feature Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ReFocusColors.surfaceStrong)
                        .border(BorderStroke(1.dp, ReFocusColors.border), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "PREMIUM FEATURE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = ReFocusColors.accent
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Value Proposition Description
                Text(
                    text = "Stay focused and prevent infinite scrolling. When enabled, Strict Mode automatically closes reels apps the moment your daily limit is reached.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp
                    ),
                    color = ReFocusColors.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action: Unlock with Premium
                Button(
                    onClick = {
                        onDismissRequest()
                        onGetPremiumClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReFocusColors.accent,
                        contentColor = ReFocusColors.textInverse
                    )
                ) {
                    Text(
                        text = "Unlock with Premium",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action: Dismiss
                TextButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Maybe Later",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        ),
                        color = ReFocusColors.textMuted
                    )
                }
            }
        }
    }
}
