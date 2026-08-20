package com.reeltrak.app.features.auth.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

@Composable
fun LogoutDialog(
    countdown: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("Preparing to sign out", fontWeight = FontWeight.Bold)
        },
        text = {
            Text("This will clear local data from this device to protect your privacy. Backing up today's progress...\n\nTime remaining: $countdown seconds")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = countdown == 0
            ) {
                Text(if (countdown > 0) "Confirm ($countdown)" else "Confirm Logout")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
