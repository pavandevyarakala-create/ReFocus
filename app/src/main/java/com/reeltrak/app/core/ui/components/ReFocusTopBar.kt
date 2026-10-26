package com.reeltrak.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.reeltrak.app.core.ui.theme.ReFocusTheme

/**
 * Re:Focus Floating Persistent Top Bar.
 *
 * Implements a persistent top-level UI surface:
 *
 * ┌────────────────────────────────────────┐
 * │  Dashboard                    Re:Focus │
 * └────────────────────────────────────────┘
 *
 * LEFT  = Current page name
 * RIGHT = Re:Focus brand name
 *
 * Principles:
 * - Floating surface above the page (does not touch screen edges)
 * - Persistent while scrollable content moves underneath
 * - Restrained shadow, rounded corners consistent with existing UI
 * - Zero decorative clutter (no profile icons, no avatars, no subtitles, no action icons)
 */
@Composable
fun ReFocusTopBar(
    title: String,
    modifier: Modifier = Modifier,
    brandName: String = "Re:Focus",
    subtitle: String? = null,
    onProfileClick: (() -> Unit)? = null,
    profilePictureUri: String? = null,
    displayName: String? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val colors = ReFocusTheme.colors
    val shape = ReFocusTheme.Dimens.TopBarShape

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ReFocusTheme.Dimens.TopBarHeight)
            .shadow(
                elevation = 4.dp,
                shape = shape,
                ambientColor = colors.shadowAmbient.copy(alpha = 0.08f),
                spotColor = colors.shadowSpot.copy(alpha = 0.08f)
            )
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: Current Page Name
            Text(
                text = title,
                style = ReFocusTheme.Typography.TopBarPageTitle.copy(
                    color = colors.textPrimary
                )
            )

            // RIGHT: Re:Focus Brand Name with Signature Lime Dot
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(colors.accent, RoundedCornerShape(1.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = brandName,
                    style = ReFocusTheme.Typography.TopBarBrandName.copy(
                        color = colors.textSecondary
                    )
                )
            }
        }
    }
}
