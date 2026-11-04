package com.reeltrak.app.core.auth

/** Keeps OAuth configuration variant-specific and prevents fallback to another Firebase project. */
internal fun resolveGoogleWebClientId(environment: String, configuredId: String?): String {
    if (environment != "prod" && environment != "staging") {
        throw IllegalStateException("Google Sign-In is unavailable: unknown app environment.")
    }
    val value = configuredId?.trim().orEmpty()
    if (value.isEmpty()) {
        throw IllegalStateException(
            "Google Sign-In configuration missing for $environment. " +
                "Verify that this build variant has its matching google-services.json and Web OAuth client."
        )
    }
    if (!value.endsWith(".apps.googleusercontent.com")) {
        throw IllegalStateException(
            "Google Sign-In configuration invalid for $environment. " +
                "The generated Web OAuth client ID is malformed; verify the matching google-services.json."
        )
    }
    return value
}
