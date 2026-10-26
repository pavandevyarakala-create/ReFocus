package com.reeltrak.app.core.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.ViewTreeObserver
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class UserAuthData(
    val email: String,
    val displayName: String,
    val profilePictureUri: String
)

sealed class GoogleAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UserCancelled(message: String = "Sign-in was cancelled") : GoogleAuthException(message)
    class NetworkError(message: String = "Network error. Please check your internet connection.", cause: Throwable? = null) : GoogleAuthException(message, cause)
    class NoAccountsAvailable(message: String = "Google Sign-In was unable to retrieve credentials. Please verify your Google account or Play Services.", cause: Throwable? = null) : GoogleAuthException(message, cause)
    class ConfigurationError(message: String = "Google Sign-In configuration error. Please verify Google Play Services and Web Client ID.", cause: Throwable? = null) : GoogleAuthException(message, cause)
    class FirebaseAuthFailed(message: String, cause: Throwable? = null) : GoogleAuthException(message, cause)
    class UnexpectedError(message: String, cause: Throwable? = null) : GoogleAuthException(message, cause)
}

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val credentialManager = CredentialManager.create(context)

    private tailrec fun findActivity(ctx: Context): Activity? = when (ctx) {
        is Activity -> ctx
        is ContextWrapper -> findActivity(ctx.baseContext)
        else -> null
    }

    /**
     * Resolves the Web Client ID dynamically from resources (generated from google-services.json)
     * with fallback to the configured OAuth client ID. Cached via lazy evaluation to avoid repeated reflection.
     */
    private val webClientId: String by lazy {
        try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) {
                context.getString(resId)
            } else {
                "524118238647-fhocd7sf7t3nkmdbbanf3a33bgqcgj90.apps.googleusercontent.com"
            }
        } catch (e: Exception) {
            "524118238647-fhocd7sf7t3nkmdbbanf3a33bgqcgj90.apps.googleusercontent.com"
        }
    }

    private fun isCancellationException(throwable: Throwable): Boolean {
        if (throwable is GetCredentialCancellationException || throwable is GetCredentialInterruptedException) {
            return true
        }
        val msg = throwable.message ?: ""
        val type = if (throwable is GetCredentialCustomException) throwable.type else ""
        return type.contains("USER_CANCELED", ignoreCase = true) ||
               type.contains("CANCELED", ignoreCase = true) ||
               type.contains("CANCELLED", ignoreCase = true) ||
               msg.contains("user_canceled", ignoreCase = true) ||
               msg.contains("canceled by user", ignoreCase = true) ||
               msg.contains("cancelled by user", ignoreCase = true) ||
               msg.contains("Activity is canceled", ignoreCase = true) ||
               msg.contains("Activity is cancelled", ignoreCase = true)
    }

    suspend fun signIn(
        callerContext: Context = context,
        tapTimestampMs: Long = android.os.SystemClock.elapsedRealtime()
    ): Result<UserAuthData> {
        val serverClientId = webClientId
        val activityContext = findActivity(callerContext) ?: callerContext
        Log.d("GoogleAuth", "Initiating Google Sign-In with Web Client ID: $serverClientId, Context is Activity: ${activityContext is Activity}")

        try {
            val tReqStart = android.os.SystemClock.elapsedRealtime()
            Log.d("AUTH_PERF", "[AUTH_PERF] Stage 1 (Tap -> Credential Manager request start): ${tReqStart - tapTimestampMs}ms")

            // Build options: GetSignInWithGoogleOption for explicit button clicks as recommended by Google Identity documentation.
            // Do not bundle GetSignInWithGoogleOption and GetGoogleIdOption in a single request, as they declare the same
            // underlying credential type and cause Play Services option collision delays and NoCredentialException.
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId)
                .build()

            val primaryRequest = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val activity = findActivity(callerContext)
            val decorView = activity?.window?.decorView

            var tPickerShownMs = 0L
            var tPickerDismissedMs = 0L
            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (!hasFocus && tPickerShownMs == 0L) {
                    tPickerShownMs = android.os.SystemClock.elapsedRealtime()
                } else if (hasFocus && tPickerShownMs > 0L && tPickerDismissedMs == 0L) {
                    tPickerDismissedMs = android.os.SystemClock.elapsedRealtime()
                }
            }

            val result = try {
                val tCredStart = android.os.SystemClock.elapsedRealtime()
                val response = withContext(Dispatchers.Main) {
                    try {
                        decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
                        credentialManager.getCredential(
                            context = activityContext,
                            request = primaryRequest
                        )
                    } finally {
                        if (decorView?.viewTreeObserver?.isAlive == true) {
                            decorView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
                        }
                    }
                }
                val tCredDone = android.os.SystemClock.elapsedRealtime()
                if (tPickerShownMs > 0L && tPickerDismissedMs >= tPickerShownMs) {
                    val pickerLaunchMs = tPickerShownMs - tCredStart
                    val userInteractionMs = tPickerDismissedMs - tPickerShownMs
                    val tokenReturnMs = tCredDone - tPickerDismissedMs
                    Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2a (Credential Manager launch -> Account Picker UI shown): ${pickerLaunchMs}ms")
                    Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2b (User account selection interaction time): ${userInteractionMs}ms")
                    Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2c (Account selection -> Credential result returned): ${tokenReturnMs}ms")
                }
                Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2 (Credential Manager picker/retrieval total): ${tCredDone - tCredStart}ms")
                response
            } catch (e: Exception) {
                if (isCancellationException(e)) {
                    throw e
                }
                // Fallback attempt: If primary SIWG request throws NoCredentialException or transient custom exception,
                // fallback to requesting GetGoogleIdOption explicitly.
                if (e is NoCredentialException || e is GetCredentialCustomException) {
                    Log.w("GoogleAuth", "Primary SIWG request encountered ${e.javaClass.simpleName} (${e.message}). Falling back to GetGoogleIdOption...", e)
                    val googleIdOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(serverClientId)
                        .setAutoSelectEnabled(false)
                        .build()

                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build()

                    tPickerShownMs = 0L
                    tPickerDismissedMs = 0L
                    val tFbStart = android.os.SystemClock.elapsedRealtime()
                    val response = withContext(Dispatchers.Main) {
                        try {
                            decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
                            credentialManager.getCredential(
                                context = activityContext,
                                request = fallbackRequest
                            )
                        } finally {
                            if (decorView?.viewTreeObserver?.isAlive == true) {
                                decorView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
                            }
                        }
                    }
                    val tFbDone = android.os.SystemClock.elapsedRealtime()
                    if (tPickerShownMs > 0L && tPickerDismissedMs >= tPickerShownMs) {
                        val pickerLaunchMs = tPickerShownMs - tFbStart
                        val userInteractionMs = tPickerDismissedMs - tPickerShownMs
                        val tokenReturnMs = tFbDone - tPickerDismissedMs
                        Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2a (Fallback launch -> Account Picker UI shown): ${pickerLaunchMs}ms")
                        Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2b (Fallback user account selection interaction time): ${userInteractionMs}ms")
                        Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2c (Fallback account selection -> Credential result returned): ${tokenReturnMs}ms")
                    }
                    Log.d("AUTH_PERF", "[AUTH_PERF] Stage 2 (Fallback picker/retrieval total): ${tFbDone - tFbStart}ms")
                    response
                } else {
                    throw e
                }
            }

            val tParseStart = android.os.SystemClock.elapsedRealtime()
            val googleCredentialResult = handleSignInResponse(result)
            if (googleCredentialResult.isFailure) {
                return Result.failure(googleCredentialResult.exceptionOrNull() ?: Exception("Sign-in failed"))
            }

            val (rawGoogleIdToken, userData) = googleCredentialResult.getOrNull()!!
            val tParseDone = android.os.SystemClock.elapsedRealtime()
            Log.d("AUTH_PERF", "[AUTH_PERF] Stage 3 (Credential parsing -> ID token): ${tParseDone - tParseStart}ms")
            Log.d("FirebaseAuth", "Initiating Firebase Auth with Google ID Token...")

            return withContext(Dispatchers.IO) {
                try {
                    val tFirebase = android.os.SystemClock.elapsedRealtime()
                    val firebaseCredential = GoogleAuthProvider.getCredential(rawGoogleIdToken, null)
                    val authResult = Firebase.auth.signInWithCredential(firebaseCredential).await()
                    val tAuthDone = android.os.SystemClock.elapsedRealtime()
                    Log.d("FirebaseAuth", "Firebase Auth successful for user: ${authResult.user?.uid}")
                    Log.d("AUTH_PERF", "[AUTH_PERF] Stage 4 (Firebase signInWithCredential): ${tAuthDone - tFirebase}ms (Total so far: ${tAuthDone - tapTimestampMs}ms)")
                    Result.success(userData)
                } catch (e: FirebaseNetworkException) {
                    Log.e("FirebaseAuth", "Firebase Network Exception", e)
                    Result.failure(GoogleAuthException.NetworkError("Network error connecting to Firebase", e))
                } catch (e: FirebaseAuthInvalidCredentialsException) {
                    Log.e("FirebaseAuth", "Firebase Invalid Credentials", e)
                    Result.failure(GoogleAuthException.FirebaseAuthFailed("Invalid credentials. Please try signing in again.", e))
                } catch (e: FirebaseAuthInvalidUserException) {
                    Log.e("FirebaseAuth", "Firebase Invalid User", e)
                    Result.failure(GoogleAuthException.FirebaseAuthFailed("Account has been disabled or removed.", e))
                } catch (e: Exception) {
                    Log.e("FirebaseAuth", "Firebase Auth failed", e)
                    Result.failure(GoogleAuthException.FirebaseAuthFailed(e.message ?: "Firebase authentication failed", e))
                }
            }
        } catch (e: Throwable) {
            if (isCancellationException(e)) {
                Log.d("GoogleAuth", "Sign-in cancelled by user (${e.javaClass.simpleName}: ${e.message})")
                return Result.failure(GoogleAuthException.UserCancelled())
            }

            when (e) {
                is NoCredentialException -> {
                    Log.w("GoogleAuth", "NoCredentialException: ${e.message}", e)
                    return Result.failure(
                        GoogleAuthException.NoAccountsAvailable(
                            message = "No Google account found on this device.",
                            cause = e
                        )
                    )
                }
                is GetCredentialProviderConfigurationException -> {
                    Log.e("GoogleAuth", "Credential provider configuration error", e)
                    return Result.failure(
                        GoogleAuthException.ConfigurationError(
                            message = "Google Play Services is not configured or needs update.",
                            cause = e
                        )
                    )
                }
                is GetCredentialCustomException -> {
                    Log.e("GoogleAuth", "Custom credential exception: ${e.type}", e)
                    return Result.failure(GoogleAuthException.UnexpectedError("Sign in failed: ${e.message ?: e.type}", e))
                }
                is GetCredentialUnknownException -> {
                    Log.e("GoogleAuth", "Unknown credential exception", e)
                    return Result.failure(GoogleAuthException.UnexpectedError("Google Sign-In encountered an issue: ${e.message}", e))
                }
                is GetCredentialException -> {
                    Log.e("GoogleAuth", "GetCredentialException: ${e.javaClass.simpleName} - ${e.message}", e)
                    return Result.failure(GoogleAuthException.UnexpectedError(e.message ?: "Sign-in failed", e))
                }
                else -> {
                    Log.e("GoogleAuth", "Unexpected exception during Google Sign In", e)
                    return Result.failure(GoogleAuthException.UnexpectedError(e.message ?: "Unexpected error occurred", e))
                }
            }
        }
    }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Firebase.auth.signOut()
        } catch (e: Exception) {
            Log.e("GoogleAuth", "Firebase.auth.signOut() failed", e)
        }

        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Result.success(Unit)
        } catch (e: ClearCredentialException) {
            Log.e("GoogleAuth", "ClearCredentialException during sign out", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("GoogleAuth", "Unexpected Exception during sign out", e)
            Result.failure(e)
        }
    }

    private fun handleSignInResponse(result: GetCredentialResponse): Result<Pair<String, UserAuthData>> {
        val credential = result.credential

        return if (credential is androidx.credentials.CustomCredential &&
            (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
             credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL)) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val userData = UserAuthData(
                    email = googleIdTokenCredential.id ?: "Unknown Email",
                    displayName = googleIdTokenCredential.displayName ?: "User",
                    profilePictureUri = googleIdTokenCredential.profilePictureUri?.toString() ?: ""
                )
                Result.success(Pair(googleIdTokenCredential.idToken, userData))
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("GoogleAuth", "Error parsing GoogleIdTokenCredential data", e)
                Result.failure(GoogleAuthException.ConfigurationError("Failed to parse Google account credentials", e))
            } catch (e: Exception) {
                Log.e("GoogleAuth", "Unexpected error parsing credential", e)
                Result.failure(GoogleAuthException.UnexpectedError("Failed to process Google sign-in response", e))
            }
        } else {
            val errorMsg = "Unexpected credential type: ${credential.type}"
            Log.e("GoogleAuth", errorMsg)
            Result.failure(GoogleAuthException.UnexpectedError(errorMsg))
        }
    }
}
