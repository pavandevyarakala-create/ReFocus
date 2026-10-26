package com.reeltrak.app.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
 * Clean, user-friendly dialog displayed when an unauthenticated Free Mode user
 * taps any action or CTA that initiates Premium or payments.
 *
 * Requirements:
 * - Detects UNAUTHENTICATED_FREE state.
 * - Shows no technical errors or stack traces.
 * - Title: "Login required"
 * - Message: "To continue with Premium, please log in to your account first."
 * - Primary CTA: "Login & Continue"
 * - Secondary CTA: "Cancel"
 * - Consistent with Re:Focus design tokens.
 */
@Composable
fun LoginRequiredDialog(
    onDismiss: () -> Unit,
    onLoginAndContinue: () -> Unit
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
                    // Header Lock Icon
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Login Required",
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title
                    Text(
                        text = "Login required",
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
                        text = "To continue with Premium, please log in to your account first.",
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Primary Action: Login & Continue
                    ReFocusFloatingPillCta(
                        text = "Login & Continue",
                        onClick = onLoginAndContinue,
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
