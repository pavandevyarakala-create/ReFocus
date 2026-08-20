package com.reeltrak.app.core.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

const val WEB_CLIENT_ID = "524118238647-fhocd7sf7t3nkmdbbanf3a33bgqcgj90.apps.googleusercontent.com"

data class UserAuthData(
    val idToken: String,
    val email: String,
    val displayName: String,
    val profilePictureUri: String
)

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(): Result<UserAuthData> = withContext(Dispatchers.IO) {
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                context = context,
                request = request
            )

            val authDataResult = handleSignInResponse(result)
            
            if (authDataResult.isSuccess) {
                val userData = authDataResult.getOrNull()!!
                Log.d("FirebaseAuth", "Initiating Firebase Auth with Google ID Token...")
                
                try {
                    val firebaseCredential = GoogleAuthProvider.getCredential(userData.idToken, null)
                    val authResult = Firebase.auth.signInWithCredential(firebaseCredential).await()
                    Log.d("FirebaseAuth", "Firebase Auth successful for user: ${authResult.user?.uid}")
                    Result.success(userData)
                } catch (e: Exception) {
                    Log.e("FirebaseAuth", "Firebase Auth failed", e)
                    Result.failure(e)
                }
            } else {
                authDataResult
            }
        } catch (e: GetCredentialException) {
            Log.e("AuthCrashDebug", "GetCredentialException during sign in", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("AuthCrashDebug", "Unexpected Exception during sign in", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        // Always try to sign out of Firebase first so we don't leave a ghost session
        try {
            Firebase.auth.signOut()
        } catch (e: Exception) {
            Log.e("AuthCrashDebug", "Firebase.auth.signOut() failed", e)
        }

        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Result.success(Unit)
        } catch (e: ClearCredentialException) {
            Log.e("AuthCrashDebug", "ClearCredentialException during sign out", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("AuthCrashDebug", "Unexpected Exception during sign out", e)
            Result.failure(e)
        }
    }

    private fun handleSignInResponse(result: GetCredentialResponse): Result<UserAuthData> {
        val credential = result.credential

        return if (credential is androidx.credentials.CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                Result.success(
                    UserAuthData(
                        idToken = googleIdTokenCredential.idToken,
                        email = googleIdTokenCredential.id ?: "Unknown Email",
                        displayName = googleIdTokenCredential.displayName ?: "User",
                        profilePictureUri = googleIdTokenCredential.profilePictureUri?.toString() ?: ""
                    )
                )
            } catch (e: Exception) {
                Log.e("AuthCrashDebug", "Error parsing GoogleIdTokenCredential data", e)
                Result.failure(e)
            }
        } else {
            val errorMsg = "Unexpected credential type: ${credential.type}"
            Log.e("AuthCrashDebug", errorMsg)
            Result.failure(Exception(errorMsg))
        }
    }
}
