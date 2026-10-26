package com.reeltrak.app.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsRow(
    title: String,
    icon: ImageVector? = null,
    description: String? = null,
    currentValue: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                color = titleColor
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        if (currentValue != null) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = currentValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(16.dp))
            trailingContent()
        }
    }
}
