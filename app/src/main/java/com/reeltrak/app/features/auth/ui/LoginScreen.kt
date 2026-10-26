package com.reeltrak.app.features.auth.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reeltrak.app.R
import com.reeltrak.app.features.auth.LoginState
import com.reeltrak.app.features.auth.LoginViewModel
import com.reeltrak.app.features.auth.components.GoogleSignInButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LoginScreen(
    onNavigateToMain: () -> Unit,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showEmailForm by remember { mutableStateOf(false) }

    // Consent Checkbox State - default is false for a new session
    var isConsentAccepted by remember { mutableStateOf(false) }
    var showConsentError by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is LoginState.Success || uiState is LoginState.FreeMode) {
            onNavigateToMain()
        }
    }

    if (uiState is LoginState.AccountRestricted) {
        AccountRestrictedScreen(
            onTryAnotherAccount = {
                viewModel.dismissRestrictedState()
                email = ""
                password = ""
            }
        )
        return
    }

    if (uiState is LoginState.AccountDeleted) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeletedState() },
            title = {
                Text(
                    text = "Account Deleted",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "This account has been permanently deleted and its data has been purged. You can register a new account to use Re:Focus.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissDeletedState() }) {
                    Text("OK")
                }
            }
        )
    }

    if (uiState is LoginState.PendingDeletion) {
        val pendingState = uiState as LoginState.PendingDeletion
        val formattedDate = remember(pendingState.deletionScheduledFor) {
            val scheduledMs = pendingState.deletionScheduledFor
            if (scheduledMs != null && scheduledMs > 0) {
                SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    .format(Date(scheduledMs))
            } else {
                "7 days from request"
            }
        }

        AlertDialog(
            onDismissRequest = { /* Force explicit user selection */ },
            title = {
                Text(
                    text = "Account Scheduled for Deletion",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column {
                    Text(
                        text = "This account is currently scheduled for permanent deletion on:\n$formattedDate\n\n" +
                               "Would you like to cancel deletion and restore your account, or keep it scheduled for deletion?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.restoreAccount(pendingState.userData)
                    }
                ) {
                    Text("Restore Account")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.keepScheduledDeletion()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Keep Scheduled & Sign Out")
                }
            }
        )
    }

    val scrollState = rememberScrollState()

    // Helper validation function before any auth action
    val validateConsentAndProceed: (() -> Unit) -> Unit = { authAction ->
        if (!isConsentAccepted) {
            showConsentError = true
        } else {
            showConsentError = false
            authAction()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // App Icon
            Image(
                painter = painterResource(id = R.drawable.ic_refocus_icon),
                contentDescription = stringResource(id = R.string.app_name),
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Minimal Tagline
            Text(
                text = "Reclaim your attention from endless scrolling.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Primary Google Sign-In CTA
            GoogleSignInButton(
                state = uiState,
                onSignInClick = {
                    if (uiState !is LoginState.Loading) {
                        val tapTimestamp = android.os.SystemClock.elapsedRealtime()
                        validateConsentAndProceed {
                            val activity = (context as? Activity)
                                ?: run {
                                    var ctx: Context? = context
                                    while (ctx is ContextWrapper) {
                                        if (ctx is Activity) return@run ctx
                                        ctx = ctx.baseContext
                                    }
                                    null
                                }
                            viewModel.signIn(activity ?: context, tapTimestamp)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Minimal Email Alternative
            if (!showEmailForm) {
                Spacer(modifier = Modifier.height(14.dp))
                TextButton(
                    onClick = { showEmailForm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Sign in with email",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = showEmailForm,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState !is LoginState.Loading
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    validateConsentAndProceed {
                                        viewModel.signInWithEmailPassword(email, password)
                                    }
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState !is LoginState.Loading
                        )

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                validateConsentAndProceed {
                                    viewModel.signInWithEmailPassword(email, password)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = uiState !is LoginState.Loading
                        ) {
                            if (uiState is LoginState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Sign In", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Unobtrusive Terms of Service & Privacy Policy Consent Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isConsentAccepted,
                    onCheckedChange = { checked ->
                        isConsentAccepted = checked
                        if (checked) {
                            showConsentError = false
                        }
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = if (showConsentError && !isConsentAccepted) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                )

                val consentFullText = "I agree to the Terms of Service and Privacy Policy"
                val termsSpan = "Terms of Service"
                val privacySpan = "Privacy Policy"

                val termsStart = consentFullText.indexOf(termsSpan)
                val termsEnd = termsStart + termsSpan.length
                val privacyStart = consentFullText.indexOf(privacySpan)
                val privacyEnd = privacyStart + privacySpan.length

                val linkColor = MaterialTheme.colorScheme.primary
                val textColor = MaterialTheme.colorScheme.onSurfaceVariant

                val consentAnnotatedString = buildAnnotatedString {
                    append(consentFullText)
                    if (termsStart >= 0) {
                        addStyle(
                            style = SpanStyle(
                                color = linkColor,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline
                            ),
                            start = termsStart,
                            end = termsEnd
                        )
                        addStringAnnotation(
                            tag = "TERMS",
                            annotation = "terms",
                            start = termsStart,
                            end = termsEnd
                        )
                    }
                    if (privacyStart >= 0) {
                        addStyle(
                            style = SpanStyle(
                                color = linkColor,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline
                            ),
                            start = privacyStart,
                            end = privacyEnd
                        )
                        addStringAnnotation(
                            tag = "PRIVACY",
                            annotation = "privacy",
                            start = privacyStart,
                            end = privacyEnd
                        )
                    }
                }

                ClickableText(
                    text = consentAnnotatedString,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = textColor
                    ),
                    onClick = { offset ->
                        consentAnnotatedString.getStringAnnotations(start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                when (annotation.tag) {
                                    "TERMS" -> onNavigateToTerms()
                                    "PRIVACY" -> onNavigateToPrivacy()
                                }
                            } ?: run {
                                isConsentAccepted = !isConsentAccepted
                                if (isConsentAccepted) {
                                    showConsentError = false
                                }
                            }
                    },
                    modifier = Modifier.padding(start = 2.dp)
                )
            }

            // Inline Consent Error
            AnimatedVisibility(
                visible = showConsentError && !isConsentAccepted,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    text = "Please agree to the Terms of Service and Privacy Policy to continue.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }

            // Authentication Error Display
            if (uiState is LoginState.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = (uiState as LoginState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = {
                    viewModel.continueAsFree()
                    onNavigateToMain()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "Continue with Free Mode",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
