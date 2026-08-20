package com.reeltrak.app.features.auth.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.reeltrak.app.R
import com.reeltrak.app.features.auth.LoginState

@Composable
fun GoogleSignInButton(
    state: LoginState,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onSignInClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = state !is LoginState.Loading,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state is LoginState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                // You'll need to add a google_logo drawable resource
                // Icon(
                //     painter = painterResource(id = R.drawable.ic_google_logo),
                //     contentDescription = "Google Logo",
                //     modifier = Modifier.size(24.dp),
                //     tint = Color.Unspecified
                // )
                // For now, using a placeholder text or a simple icon
                Text(text = "G", style = MaterialTheme.typography.titleLarge)
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = if (state is LoginState.Loading) "Signing in..." else "Sign in with Google",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
