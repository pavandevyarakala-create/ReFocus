package com.reeltrak.app.core.ads

/**
 * Configuration and identifier registry for Google Mobile Ads (AdMob).
 *
 * CRITICAL SAFETY NOTICE:
 * - Active development, testing, and debugging builds MUST use the official Google Test Ad Unit ID
 *   to comply with Google AdMob policy and prevent account suspension or invalid activity flags.
 * - The Production Rewarded Ad Unit ID is placed here for production readiness, but is guarded by
 *   [USE_TEST_ADS] which is set to true for development/testing.
 */
object AdMobConfig {

    /**
     * Re:Focus AdMob Application ID (declared in AndroidManifest.xml).
     */
    const val APP_ID: String = "ca-app-pub-5284347714670334~9887795933"

    /**
     * Official Google Test Rewarded Ad Unit ID for Android.
     * This ID will always return valid sample rewarded video ads in development environments.
     */
    const val TEST_REWARDED_AD_UNIT_ID: String = "ca-app-pub-3940256099942544/5224354917"

    /**
     * Re:Focus Production Rewarded Ad Unit ID.
     * Ready for live deployment. DO NOT enable during local development or emulator testing.
     */
    const val PROD_REWARDED_AD_UNIT_ID: String = "ca-app-pub-5284347714670334/2460732054"

    /**
     * Controls whether Test or Production ads are loaded.
     * Set to true by default across BOTH debug and release builds during Google Play and AdMob
     * verification to ensure official Google test ads are served reliably without No-Fill errors.
     *
     * Once production AdMob ad-unit approval is complete, toggle this to false for live serving.
     */
    var USE_TEST_ADS: Boolean = true

    /**
     * Returns the active Rewarded Ad Unit ID to be loaded by the SDK.
     */
    val rewardedAdUnitId: String
        get() = if (USE_TEST_ADS) {
            TEST_REWARDED_AD_UNIT_ID
        } else {
            PROD_REWARDED_AD_UNIT_ID
        }
}
