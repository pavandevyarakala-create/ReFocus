package com.reeltrak.app.features.auth.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.R
import com.reeltrak.app.features.auth.LoginState

import com.reeltrak.app.core.ui.theme.ReFocusColors

@Composable
fun GoogleSignInButton(
    state: LoginState,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoading = state is LoginState.Loading

    Button(
        onClick = onSignInClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            ),
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 1.dp,
            focusedElevation = 1.dp,
            hoveredElevation = 1.dp,
            disabledElevation = 0.dp
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Signing in...",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_google_logo),
                    contentDescription = "Google Logo",
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Continue with Google",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
