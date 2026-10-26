package com.reeltrak.app.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reeltrak.app.core.ui.theme.ReFocusBrandTheme
import com.reeltrak.app.core.ui.theme.ReFocusFloatingPillCta
import com.reeltrak.app.core.ui.theme.ReFocusTheme

/**
 * Polished login/upgrade dialog displayed when a Free user attempts to use or configure Daily Limit.
 *
 * Requirements:
 * - Unauthenticated Free User:
 *   Title: "Login required"
 *   Message: "Please log in to use Daily Limit and continue with Premium features."
 *   Primary CTA: "Login & Continue"
 *   Secondary CTA: "Cancel"
 *
 * - Authenticated Free User:
 *   Title: "Premium feature"
 *   Message: "Daily Limit is available with Trial or Premium."
 *   Primary CTA: "View Premium"
 *   Secondary CTA: "Cancel"
 */
@Composable
fun DailyLimitEntitlementDialog(
    isUnauthenticated: Boolean,
    onDismiss: () -> Unit,
    onPrimaryAction: () -> Unit
) {
    ReFocusBrandTheme(darkTheme = false) {
        val colors = ReFocusTheme.colors
        val containerShape = RoundedCornerShape(24.dp)

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Surface(
                shape = containerShape,
                color = colors.canvas,
                border = BorderStroke(1.dp, colors.lineHairline),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = containerShape,
                        ambientColor = colors.shadowAmbient,
                        spotColor = colors.shadowSpot
                    )
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
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isUnauthenticated) Icons.Outlined.Lock else Icons.Outlined.Star,
                            contentDescription = if (isUnauthenticated) "Login Required" else "Premium Feature",
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title
                    Text(
                        text = if (isUnauthenticated) "Login required" else "Premium feature",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = colors.inkPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Explanatory Message
                    Text(
                        text = if (isUnauthenticated) {
                            "Please log in to use Daily Limit and continue with Premium features."
                        } else {
                            "Daily Limit is available with Trial or Premium."
                        },
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Primary Action
                    ReFocusFloatingPillCta(
                        text = if (isUnauthenticated) "Login & Continue" else "View Premium",
                        onClick = onPrimaryAction,
                        height = 48.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secondary Action: Cancel
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            ),
                            color = colors.inkMuted
                        )
                    }
                }
            }
        }
    }
}
