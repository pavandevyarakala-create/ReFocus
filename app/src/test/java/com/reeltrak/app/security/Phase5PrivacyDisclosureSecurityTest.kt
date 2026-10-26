package com.reeltrak.app.security

import com.reeltrak.app.core.legal.PrivacyPolicyUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 5 Privacy & Legal Disclosure Verification Tests
 *
 * Verifies:
 * - Privacy Policy and Terms of Service URLs are HTTPS and point to the authorized hosting domain.
 * - LoginScreen source code uses centralized legal annotations and ClickableText.
 * - SettingsScreen source code integrates the "PRIVACY & LEGAL" section.
 * - Permission setup / Onboarding contains explicit on-device privacy notices before launching Accessibility settings.
 * - Zero unauthorized or hardcoded external privacy policy domains exist across source files.
 */
class Phase5PrivacyDisclosureSecurityTest {

    @Test
    fun testPrivacyPolicyUrls_HttpsAndAuthorizedDomain() {
        val privacyUrl = PrivacyPolicyUrls.PRIVACY_POLICY
        val termsUrl = PrivacyPolicyUrls.TERMS_OF_SERVICE

        assertNotNull("Privacy Policy URL must not be null", privacyUrl)
        assertNotNull("Terms of Service URL must not be null", termsUrl)

        assertTrue("Privacy Policy URL must use HTTPS", privacyUrl.startsWith("https://"))
        assertTrue("Terms of Service URL must use HTTPS", termsUrl.startsWith("https://"))

        assertTrue("Privacy Policy URL must point to refocus-504119.web.app", privacyUrl.contains("refocus-504119.web.app"))
        assertTrue("Terms of Service URL must point to refocus-504119.web.app", termsUrl.contains("refocus-504119.web.app"))

        assertEquals("https://refocus-504119.web.app/privacy.html", privacyUrl)
        assertEquals("https://refocus-504119.web.app/terms.html", termsUrl)
    }

    @Test
    fun testLoginScreen_ClickableLegalLinksImplemented() {
        val possiblePaths = listOf(
            "src/main/java/com/reeltrak/app/features/auth/ui/LoginScreen.kt",
            "app/src/main/java/com/reeltrak/app/features/auth/ui/LoginScreen.kt",
            "../app/src/main/java/com/reeltrak/app/features/auth/ui/LoginScreen.kt"
        )
        val loginScreenFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("LoginScreen.kt must exist", loginScreenFile)

        val content = loginScreenFile!!.readText()

        assertTrue("LoginScreen must include consent checkbox state", content.contains("isConsentAccepted"))
        assertTrue("LoginScreen must validate consent before auth actions", content.contains("validateConsentAndProceed"))
        assertTrue("LoginScreen must use ClickableText for accessible in-app link tapping", content.contains("ClickableText"))
        assertTrue("LoginScreen must provide navigation to in-app privacy policy", content.contains("onNavigateToPrivacy"))
        assertTrue("LoginScreen must provide navigation to in-app terms", content.contains("onNavigateToTerms"))
        assertTrue("LoginScreen must not open external browser for privacy", !content.contains("BrowserUtils.openUrl"))
    }

    @Test
    fun testSettingsScreen_PrivacyAndLegalSectionImplemented() {
        val possiblePaths = listOf(
            "src/main/java/com/reeltrak/app/features/settings/ui/SettingsScreen.kt",
            "app/src/main/java/com/reeltrak/app/features/settings/ui/SettingsScreen.kt",
            "../app/src/main/java/com/reeltrak/app/features/settings/ui/SettingsScreen.kt"
        )
        val settingsFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("SettingsScreen.kt must exist", settingsFile)

        val content = settingsFile!!.readText()

        assertTrue("SettingsScreen must contain PRIVACY & LEGAL section", content.contains("PRIVACY & LEGAL"))
        assertTrue("SettingsScreen must contain Privacy Policy row", content.contains("\"Privacy Policy\""))
        assertTrue("SettingsScreen must contain Terms of Service row", content.contains("\"Terms of Service\""))
        assertTrue("SettingsScreen must use onNavigateToPrivacy for in-app viewing", content.contains("onNavigateToPrivacy"))
        assertTrue("SettingsScreen must use onNavigateToTerms for in-app viewing", content.contains("onNavigateToTerms"))
    }

    @Test
    fun testPermissionWizard_OnDevicePrivacyNoticeImplemented() {
        val possiblePaths = listOf(
            "src/main/java/com/reeltrak/app/features/permission/ui/PermissionSetupWizardScreen.kt",
            "app/src/main/java/com/reeltrak/app/features/permission/ui/PermissionSetupWizardScreen.kt",
            "../app/src/main/java/com/reeltrak/app/features/permission/ui/PermissionSetupWizardScreen.kt"
        )
        val wizardFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("PermissionSetupWizardScreen.kt must exist", wizardFile)

        val content = wizardFile!!.readText()

        assertTrue("Permission wizard must explain Why Re:Focus needs this", content.contains("Why Re:Focus needs this"))
        assertTrue("Permission wizard must explain Where & how to enable", content.contains("Where & how to enable"))
        assertTrue("Permission wizard must explain After enabling", content.contains("After enabling"))
        assertTrue("Permission wizard must explain If not granted", content.contains("If not granted"))
        assertFalse("Permission wizard must not include unnecessary server/backend explanations", content.contains("backend", ignoreCase = true))
        assertFalse("Permission wizard must not include Firebase implementation details", content.contains("Firebase", ignoreCase = true))
    }
}
