package com.reeltrak.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoogleWebClientIdTest {
    @Test
    fun `each flavor uses its supplied Firebase Web client ID`() {
        val prodId = "prod-client.apps.googleusercontent.com"
        val stagingId = "staging-client.apps.googleusercontent.com"

        assertEquals(prodId, resolveGoogleWebClientId("prod", prodId))
        assertEquals(stagingId, resolveGoogleWebClientId("staging", stagingId))
    }

    @Test
    fun `missing or blank flavor configuration fails closed`() {
        listOf("prod", "staging").forEach { environment ->
            assertThrows(IllegalStateException::class.java) {
                resolveGoogleWebClientId(environment, null)
            }
            assertThrows(IllegalStateException::class.java) {
                resolveGoogleWebClientId(environment, "  ")
            }
        }
    }

    @Test
    fun `unknown environment fails closed`() {
        assertThrows(IllegalStateException::class.java) {
            resolveGoogleWebClientId("other", "configured-client")
        }
    }

    @Test
    fun `malformed generated client ID fails closed`() {
        assertThrows(IllegalStateException::class.java) {
            resolveGoogleWebClientId("prod", "not-a-web-oauth-client")
        }
    }
}
