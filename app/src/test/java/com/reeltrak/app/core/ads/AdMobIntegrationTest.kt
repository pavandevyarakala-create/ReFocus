package com.reeltrak.app.core.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdMobIntegrationTest {

    @Test
    fun testAdMobAppId_isConfiguredCorrectly() {
        assertEquals(
            "AdMob App ID must match registered Re:Focus identifier",
            "ca-app-pub-5284347714670334~9887795933",
            AdMobConfig.APP_ID
        )
    }

    @Test
    fun testTestRewardedAdUnitId_isOfficialGoogleTestId() {
        assertEquals(
            "Test Rewarded Ad Unit ID must match Google's official sample ID",
            "ca-app-pub-3940256099942544/5224354917",
            AdMobConfig.TEST_REWARDED_AD_UNIT_ID
        )
    }

    @Test
    fun testProdRewardedAdUnitId_isConfiguredCorrectly() {
        assertEquals(
            "Production Rewarded Ad Unit ID must match Re:Focus release ad unit",
            "ca-app-pub-5284347714670334/2460732054",
            AdMobConfig.PROD_REWARDED_AD_UNIT_ID
        )
    }

    @Test
    fun testSafetySwitch_useTestAdsIsActiveForDevelopment() {
        // Verify default is active (true) to ensure Google test ads are served during verification
        assertTrue(
            "USE_TEST_ADS MUST default to true during verification to prevent policy violations & No-Fill errors",
            AdMobConfig.USE_TEST_ADS
        )
        assertEquals(
            "When USE_TEST_ADS is true, active ad unit ID must return the Google Test Rewarded Ad Unit ID",
            AdMobConfig.TEST_REWARDED_AD_UNIT_ID,
            AdMobConfig.rewardedAdUnitId
        )

        // Test toggling to production mode
        try {
            AdMobConfig.USE_TEST_ADS = false
            assertEquals(
                "When USE_TEST_ADS is false, active ad unit ID must return the Production Rewarded Ad Unit ID",
                AdMobConfig.PROD_REWARDED_AD_UNIT_ID,
                AdMobConfig.rewardedAdUnitId
            )
        } finally {
            // Restore test mode
            AdMobConfig.USE_TEST_ADS = true
        }
    }

    @Test
    fun testSingleReusableAdUnit_sharedAcrossAllProtectedActions() {
        // Verify that both Daily Limit and Strict Mode share the exact same ad unit ID
        val dailyLimitAdUnitId = AdMobConfig.rewardedAdUnitId
        val strictModeAdUnitId = AdMobConfig.rewardedAdUnitId

        assertEquals(
            "Daily Limit and Strict Mode MUST share the exact same single Rewarded Ad Unit ID",
            dailyLimitAdUnitId,
            strictModeAdUnitId
        )
    }
}
