package com.reeltrak.app.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.features.auth.components.SignOutButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userData: UserAuthData,
    onSignOutClick: () -> Unit,
    onRestoreBackupClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile Picture
            AsyncImage(
                model = userData.profilePictureUri,
                contentDescription = "Profile Picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // User Name
            Text(
                text = userData.displayName ?: "No Name",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Email
            Text(
                text = userData.email ?: "No Email",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Sign Out Button
            SignOutButton(
                onSignOutClick = onSignOutClick,
                enabled = true
            )

            // Restore Backup Button
            Button(
                onClick = onRestoreBackupClick,
                modifier = Modifier.padding(top = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Restore Backup")
            }
        }
    }
}
