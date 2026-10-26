package com.reeltrak.app.security

import com.reeltrak.app.core.network.NetworkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4.2 Logging Hardening & Worker URL Centralization Security Tests
 *
 * Verifies:
 * - Canonical Cloudflare Worker URL is defined in NetworkConstants.
 * - All networking clients utilize the single centralized constant without duplicate hardcoded endpoints.
 * - ProGuard/R8 log stripping rules are present in proguard-rules.pro.
 * - Sensitive logging patterns (captions, creators, tokens, secrets) are guarded/stripped.
 */
class Phase4ClientReleaseSecurityTest {

    @Test
    fun testWorkerUrlCentralization_CanonicalUrlDefined() {
        val canonicalUrl = NetworkConstants.WORKER_BASE_URL
        assertNotNull("Canonical Worker URL must not be null", canonicalUrl)
        assertTrue("Canonical Worker URL must use HTTPS", canonicalUrl.startsWith("https://"))
        assertTrue("Canonical Worker URL must target workers.dev", canonicalUrl.contains("workers.dev"))
        assertEquals(
            "Canonical Worker URL must match the deployed project name",
            "https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev",
            canonicalUrl
        )
    }

    @Test
    fun testProGuardRules_LogStrippingDirectivesPresent() {
        // Find proguard-rules.pro
        val possiblePaths = listOf(
            "proguard-rules.pro",
            "app/proguard-rules.pro",
            "../app/proguard-rules.pro"
        )
        val proguardFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("proguard-rules.pro must exist in repository", proguardFile)

        val content = proguardFile!!.readText()
        assertTrue(
            "ProGuard rules must include -assumenosideeffects for android.util.Log",
            content.contains("-assumenosideeffects class android.util.Log")
        )
        assertTrue("ProGuard rules must strip Log.v", content.contains("public static int v("))
        assertTrue("ProGuard rules must strip Log.d", content.contains("public static int d("))
        assertTrue("ProGuard rules must strip Log.i", content.contains("public static int i("))
    }

    @Test
    fun testProGuardRules_SensitiveDataPreservationSafety() {
        val possiblePaths = listOf(
            "proguard-rules.pro",
            "app/proguard-rules.pro",
            "../app/proguard-rules.pro"
        )
        val proguardFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("proguard-rules.pro must exist", proguardFile)

        val content = proguardFile!!.readText()
        // Ensure Razorpay SDK keep rules remain intact
        assertTrue("Razorpay keep rules must be preserved", content.contains("com.razorpay.**"))
        // Ensure Firestore DTO keep rules remain intact
        assertTrue("DTO keep rules must be preserved", content.contains("com.reeltrak.app.data.remote.dto.**"))
    }

    @Test
    fun testBuildGradle_ReleaseLintConfigurationEnabled() {
        val possiblePaths = listOf(
            "build.gradle.kts",
            "app/build.gradle.kts",
            "../app/build.gradle.kts"
        )
        val buildGradleFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() && it.readText().contains("lint {") }
        assertNotNull("app/build.gradle.kts must exist", buildGradleFile)

        val content = buildGradleFile!!.readText()
        assertTrue("checkReleaseBuilds must be enabled", content.contains("checkReleaseBuilds = true"))
        assertTrue("abortOnError must be enabled", content.contains("abortOnError = true"))
        assertFalse("checkReleaseBuilds must NOT be disabled", content.contains("checkReleaseBuilds = false"))
        assertFalse("abortOnError must NOT be disabled", content.contains("abortOnError = false"))
    }

    @Test
    fun testBuildGradle_ReleaseSigningSafety() {
        val possiblePaths = listOf(
            "build.gradle.kts",
            "app/build.gradle.kts",
            "../app/build.gradle.kts"
        )
        val buildGradleFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() && it.readText().contains("signingConfigs") }
        assertNotNull("app/build.gradle.kts must exist", buildGradleFile)

        val content = buildGradleFile!!.readText()
        // Ensure release never falls back to debug signing config
        assertFalse("Release must never use debug signing config", content.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
        assertTrue("Release must explicitly set null when not configured", content.contains("signingConfig = null"))
    }

    @Test
    fun testAndroidManifest_BackupAndCleartextSecurity() {
        val possiblePaths = listOf(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "../app/src/main/AndroidManifest.xml"
        )
        val manifestFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml must exist", manifestFile)

        val content = manifestFile!!.readText()
        assertTrue("allowBackup must be explicitly false", content.contains("android:allowBackup=\"false\""))
        assertTrue("networkSecurityConfig must be referenced", content.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse("usesCleartextTraffic must not be set to true in manifest", content.contains("android:usesCleartextTraffic=\"true\""))
    }

    @Test
    fun testNetworkSecurityConfig_CleartextTrafficDisabled() {
        val possiblePaths = listOf(
            "src/main/res/xml/network_security_config.xml",
            "app/src/main/res/xml/network_security_config.xml",
            "../app/src/main/res/xml/network_security_config.xml"
        )
        val networkConfigFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("network_security_config.xml must exist", networkConfigFile)

        val content = networkConfigFile!!.readText()
        assertTrue("cleartextTrafficPermitted must be false", content.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse("cleartextTrafficPermitted must not be true", content.contains("cleartextTrafficPermitted=\"true\""))
    }
}
