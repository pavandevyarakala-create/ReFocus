package com.reeltrak.app.domain.repository

import kotlinx.coroutines.flow.Flow

interface OnboardingRepository {
    /**
     * Checks whether the user account (or device fallback) has completed or skipped the App Tour.
     * Guaranteed to return true for existing users so they are never disrupted.
     */
    suspend fun isAppTourCompleted(userId: String?): Boolean

    /**
     * Marks the App Tour as completed/skipped for the given user account both in local DataStore
     * and in Firestore (users/{uid}/onboarding/tour).
     */
    suspend fun setAppTourCompleted(userId: String?, completed: Boolean = true)
}
