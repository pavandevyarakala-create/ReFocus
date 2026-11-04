package com.reeltrak.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.reeltrak.app.domain.repository.AccountRepository
import com.reeltrak.app.domain.repository.OnboardingRepository
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding_prefs")

@Singleton
class OnboardingRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository
) : OnboardingRepository {

    private val TAG = "OnboardingRepo"

    /**
     * Account-aware evaluation of whether the user has completed or skipped the App Tour.
     *
     * Local-first evaluation:
     * - Checks local DataStore cache for the account.
     * - If not recorded, checks local firstRunDate or defaultDailyLimit to protect returning users.
     * - Never requires network or Firestore requests.
     */
    override suspend fun isAppTourCompleted(userId: String?): Boolean = withContext(Dispatchers.IO) {
        val uid = userId ?: Firebase.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            // Signed out state
            return@withContext true
        }

        val accountKey = booleanPreferencesKey("app_tour_completed_$uid")

        // 1. Check local DataStore cache for this specific account
        val localCompleted = context.onboardingDataStore.data.first()[accountKey]
        if (localCompleted == true) {
            return@withContext true
        }

        // 2. Local-first check: if firstRunDate > 0 or defaultDailyLimit > 0, existing user
        val firstRunDate = preferencesRepository.firstRunDate.first()
        if (firstRunDate > 0L) {
            Log.d(TAG, "Existing user detected via local firstRunDate for $uid. Marking tour completed locally.")
            setAppTourCompleted(uid, true)
            return@withContext true
        }

        val defaultLimit = preferencesRepository.defaultDailyLimit.first()
        if (defaultLimit > 0) {
            Log.d(TAG, "Existing user detected via defaultDailyLimit for $uid. Marking tour completed locally.")
            setAppTourCompleted(uid, true)
            return@withContext true
        }

        // Brand-new account without history or recorded onboarding
        return@withContext false
    }

    override suspend fun setAppTourCompleted(userId: String?, completed: Boolean): Unit = withContext(Dispatchers.IO) {
        val uid = userId ?: Firebase.auth.currentUser?.uid ?: return@withContext
        val accountKey = booleanPreferencesKey("app_tour_completed_$uid")

        // Save locally in DataStore (0 remote network calls)
        try {
            context.onboardingDataStore.edit { preferences ->
                preferences[accountKey] = completed
            }
            Log.d(TAG, "Saved onboarding tour status locally for $uid: $completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save onboarding preference locally for $uid", e)
        }
    }
}
