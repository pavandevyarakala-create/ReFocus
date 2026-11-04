package com.reeltrak.app.security

import com.reeltrak.app.core.network.NetworkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Environment Isolation Security Verification Tests
 *
 * Enforces strict environment separation to prevent staging builds from
 * accidentally communicating with production Firebase or Cloudflare Worker resources,
 * and prevents production builds from being pointed to staging/arbitrary endpoints.
 */
class EnvironmentIsolationSecurityTest {

    @Test
    fun testProdEnvironment_AcceptsAuthoritativeProdUrl() {
        val resolved = NetworkConstants.resolveWorkerBaseUrl(
            environment = "prod",
            configuredUrl = NetworkConstants.PROD_WORKER_BASE_URL
        )
        assertEquals(NetworkConstants.PROD_WORKER_BASE_URL, resolved)
    }

    @Test
    fun testProdEnvironment_RejectsNonProdUrl() {
        try {
            NetworkConstants.resolveWorkerBaseUrl(
                environment = "prod",
                configuredUrl = "https://refocus-razorpay-worker-staging.refocus-razorpay-worker.workers.dev"
            )
            fail("Production environment MUST reject non-production worker URL")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Production build is misconfigured") == true)
        }
    }

    @Test
    fun testStagingEnvironment_RejectsProdUrl() {
        try {
            NetworkConstants.resolveWorkerBaseUrl(
                environment = "staging",
                configuredUrl = NetworkConstants.PROD_WORKER_BASE_URL
            )
            fail("Staging environment MUST reject production worker URL to prevent contamination")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Staging build cannot point to production endpoint") == true)
        }
    }

    @Test
    fun testStagingEnvironment_RejectsPlaceholderUrl() {
        try {
            NetworkConstants.resolveWorkerBaseUrl(
                environment = "staging",
                configuredUrl = "https://PLACEHOLDER_STAGING_WORKER_URL.dev"
            )
            fail("Staging environment MUST reject placeholder worker URL")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("placeholder") == true)
        }
    }

    @Test
    fun testStagingEnvironment_RejectsInsecureHttp() {
        try {
            NetworkConstants.resolveWorkerBaseUrl(
                environment = "staging",
                configuredUrl = "http://refocus-razorpay-worker-staging.refocus-razorpay-worker.workers.dev"
            )
            fail("Staging environment MUST require HTTPS")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be HTTPS") == true)
        }
    }

    @Test
    fun testStagingEnvironment_RejectsBlankUrl() {
        try {
            NetworkConstants.resolveWorkerBaseUrl(
                environment = "staging",
                configuredUrl = "   "
            )
            fail("Blank worker URL must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("cannot be blank") == true)
        }
    }

    @Test
    fun testStagingEnvironment_AcceptsValidStagingUrl() {
        val stagingUrl = "https://refocus-razorpay-worker-staging.refocus-razorpay-worker.workers.dev"
        val resolved = NetworkConstants.resolveWorkerBaseUrl(
            environment = "staging",
            configuredUrl = stagingUrl
        )
        assertEquals(stagingUrl, resolved)
    }

    @Test
    fun testStagingServicesDescriptor_DoesNotContainProductionIdentifiers() {
        val candidates = listOf(
            File("src/staging/google-services.json"),
            File("app/src/staging/google-services.json")
        )
        val stagingFile = candidates.firstOrNull { it.exists() }
        assertTrue("app/src/staging/google-services.json must exist", stagingFile != null && stagingFile.exists())

        val content = stagingFile!!.readText()
        assertFalse("Staging descriptor MUST NOT contain prod Firebase project ID", content.contains("refocus-504119"))
        assertFalse("Staging descriptor MUST NOT contain prod Firebase project number", content.contains("524118238647"))
        assertTrue("Staging descriptor MUST target package 'com.reeltrak.app.staging'", content.contains("com.reeltrak.app.staging"))
    }

    @Test
    fun testProductionServicesDescriptor_ContainsAuthoritativeProductionIdentifiers() {
        val candidates = listOf(
            File("src/prod/google-services.json"),
            File("app/src/prod/google-services.json"),
            File("google-services.json"),
            File("app/google-services.json")
        )
        val prodFile = candidates.firstOrNull { it.exists() }
        assertTrue("Production google-services.json must exist", prodFile != null && prodFile.exists())

        val content = prodFile!!.readText()
        assertTrue("Production descriptor MUST contain prod Firebase project ID", content.contains("refocus-504119"))
        assertTrue("Production descriptor MUST contain prod Firebase project number", content.contains("524118238647"))
        assertTrue("Production descriptor MUST target package 'com.reeltrak.app'", content.contains("com.reeltrak.app"))
    }
}
