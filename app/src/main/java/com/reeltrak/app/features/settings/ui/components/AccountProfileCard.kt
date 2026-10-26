package com.reeltrak.app.features.settings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.core.ui.components.formatEpochDate
import com.reeltrak.app.core.ui.components.formatRemainingDurationText
import com.reeltrak.app.core.ui.theme.AmbientRadialBackdrop
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.core.ui.theme.specularBorder
import com.reeltrak.app.core.ui.theme.tactilePress
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo

/**
 * Cinematic Profile & Membership Hero.
 *
 * Inspired by high-end consumer editorial apps:
 * - Ambient radial lime studio glow
 * - 84dp squircle avatar container with 3D specular highlight border
 * - Crisp typography hierarchy
 * - Tactile membership pill badge (RE:FOCUS PRO // ACTIVE)
 * - Restrained status metadata
 */
@Composable
fun AccountProfileCard(
    userData: UserAuthData,
    accessState: AccessState,
    subscriptionInfo: SubscriptionInfo,
    remainingDurationMs: Long,
    onMembershipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ReFocusTheme.colors
    val avatarShape = RoundedCornerShape(26.dp)
    val pillShape = RoundedCornerShape(999.dp)

    val formattedDate = remember(subscriptionInfo.currentPeriodEnd, subscriptionInfo.trialEnd) {
        formatEpochDate(subscriptionInfo.currentPeriodEnd) ?: formatEpochDate(subscriptionInfo.trialEnd)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Avatar with 3D Specular Border and Ambient Radial Back-Glow
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center
        ) {
            // Ambient Radial Back-Glow
            AmbientRadialBackdrop(
                modifier = Modifier.size(110.dp),
                glowColor = colors.accentLime,
                glowAlpha = if (colors.isDark) 0.25f else 0.12f
            )

            // Centered Squircle Avatar Container (84dp)
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(avatarShape)
                    .background(colors.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!userData.profilePictureUri.isNullOrBlank()) {
                    AsyncImage(
                        model = userData.profilePictureUri,
                        contentDescription = "Profile Picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(avatarShape)
                    )
                } else {
                    Text(
                        text = (userData.displayName ?: userData.email ?: "U").take(1).uppercase(),
                        style = ReFocusTheme.Typography.Headline.copy(
                            color = colors.inkPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. User Identity Typography
        Text(
            text = userData.displayName?.takeIf { it.isNotBlank() } ?: "Re:Focus Member",
            style = ReFocusTheme.Typography.Headline.copy(
                color = colors.inkPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = userData.email ?: "Authenticated Account",
            style = ReFocusTheme.Typography.Body.copy(
                color = colors.inkSecondary,
                fontSize = 13.sp
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Subtle, Understated Membership Status Badge (Borderless, sits directly on canvas)
        val tierLabel = when (accessState) {
            AccessState.ACTIVE -> "PRO MEMBER"
            AccessState.TRIAL -> "7-DAY TRIAL"
            AccessState.INITIALIZING -> "SYNCING"
            else -> "FREE PLAN"
        }
        val dotColor = when (accessState) {
            AccessState.ACTIVE, AccessState.TRIAL -> colors.accentLime
            else -> colors.inkMuted
        }

        val badgeInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .clip(pillShape)
                .background(colors.surfaceElevated)
                .tactilePress(interactionSource = badgeInteraction)
                .clickable(
                    interactionSource = badgeInteraction,
                    indication = null,
                    onClick = onMembershipClick
                )
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tierLabel,
                    style = ReFocusTheme.Typography.SectionTitle.copy(
                        color = colors.inkPrimary,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}
