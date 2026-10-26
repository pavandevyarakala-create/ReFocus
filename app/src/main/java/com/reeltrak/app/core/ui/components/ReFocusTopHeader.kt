package com.reeltrak.app.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Backwards-compatible alias for [ReFocusTopBar].
 */
@Composable
fun ReFocusTopHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    brandText: String = "Re:Focus",
    action: (@Composable () -> Unit)? = null
) {
    ReFocusTopBar(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        brandName = brandText,
        trailingContent = action
    )
}
