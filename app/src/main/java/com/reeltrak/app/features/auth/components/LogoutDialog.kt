package com.reeltrak.app.features.auth.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Logout
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

@Composable
fun LogoutDialog(
    countdown: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon Header with subtle tint
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (countdown == 0)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (countdown == 0) Icons.Default.Logout else Icons.Default.HourglassTop,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = if (countdown == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Title
                Text(
                    text = "Ready to sign out?",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Exactly ONE countdown displayed
                Text(
                    text = if (countdown > 0) {
                        "Please wait $countdown second${if (countdown == 1) "" else "s"} before signing out."
                    } else {
                        "Your data is backed up. You can now sign out."
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = if (countdown > 0)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Actions: Clean button hierarchy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Secondary action: Stay / Cancel
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = "Stay",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    // Primary action: Sign Out (disabled strictly for the full 10 seconds)
                    Button(
                        onClick = onConfirm,
                        enabled = countdown == 0,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    ) {
                        Text(
                            text = "Sign Out",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
