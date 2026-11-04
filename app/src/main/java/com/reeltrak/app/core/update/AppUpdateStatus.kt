package com.reeltrak.app.core.update

/**
 * Observable status for the Google Play In-App Updates lifecycle.
 */
enum class AppUpdateStatus {
    /**
     * Normal operation. Current version is up-to-date or no update required.
     */
    UPDATE_NOT_REQUIRED,

    /**
     * Google Play confirmed a mandatory update is available. App access is locked.
     */
    UPDATE_REQUIRED,

    /**
     * The Google Play immediate update flow is currently active.
     */
    UPDATE_IN_PROGRESS,

    /**
     * The update attempt was cancelled by the user or failed. App remains strictly locked.
     */
    UPDATE_FAILED
}
