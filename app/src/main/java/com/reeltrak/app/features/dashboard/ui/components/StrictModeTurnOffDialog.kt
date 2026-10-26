package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayCircle
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
 * Polished confirmation dialog displayed when an entitled user attempts to turn off Strict Mode.
 *
 * Requirements:
 * 1. Explains cleanly that watching a short video ad is required to disable Strict Mode for today.
 * 2. Displays a primary CTA to watch the rewarded ad.
 * 3. If the ad fails to load or is unavailable, presents a friendly error state with a "Retry" CTA.
 * 4. Dismissing keeps Strict Mode enabled with zero state changes.
 */
@Composable
fun StrictModeTurnOffDialog(
    isLoading: Boolean,
    errorMessage: String? = null,
    onDismissRequest: () -> Unit,
    onWatchAdClick: () -> Unit,
    onRetryClick: () -> Unit = onWatchAdClick
) {
    val isError = errorMessage != null

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = !isLoading
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
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isError) {
                                ReFocusColors.warningText.copy(alpha = 0.12f)
                            } else {
                                ReFocusColors.accent.copy(alpha = 0.12f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.PlayCircle,
                        contentDescription = if (isError) "Ad Error" else "Watch Ad to Turn Off Strict Mode",
                        tint = if (isError) ReFocusColors.warningText else ReFocusColors.accent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = if (isError) "Ad Unavailable" else "Turn Off Strict Mode?",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    ),
                    color = ReFocusColors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Explanatory message
                Text(
                    text = if (isError) {
                        "The video ad could not be loaded right now. Strict Mode remains active to protect your focus."
                    } else {
                        "Watching a short video ad is required to turn off Strict Mode for today. This extra step helps you pause and stay intentional."
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp
                    ),
                    color = ReFocusColors.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Primary CTA (Watch Ad or Retry)
                Button(
                    onClick = {
                        if (!isLoading) {
                            if (isError) onRetryClick() else onWatchAdClick()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReFocusColors.accent,
                        contentColor = ReFocusColors.textInverse
                    ),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = ReFocusColors.textInverse,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (isError) "Retry" else "Watch Ad to Turn Off",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Secondary Dismiss / Keep On Button
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ReFocusColors.textSecondary
                    ),
                    border = BorderStroke(1.dp, ReFocusColors.border),
                    enabled = true
                ) {
                    Text(
                        text = if (isError) "Cancel" else "Keep Strict Mode On",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    )
                }
            }
        }
    }
}
