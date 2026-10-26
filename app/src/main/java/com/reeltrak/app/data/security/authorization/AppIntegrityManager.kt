package com.reeltrak.app.data.security.authorization

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.reeltrak.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates package identity and signing certificate digest.
 * Detects modified APKs, re-signed packages, and clone attacks.
 *
 * Implements real SHA-256 certificate pinning for the production release key.
 */
@Singleton
open class AppIntegrityManager @Inject constructor(
    @ApplicationContext private val context: Context?
) {
    private val TAG = "AppIntegrityManager"

    companion object {
        const val EXPECTED_PACKAGE_NAME = "com.reeltrak.app"

        /**
         * Pinned SHA-256 certificate fingerprint of the production Google Play App Signing key.
         * Google Play re-signs all distributed release bundles/APKs with this key.
         */
        const val PLAY_APP_SIGNING_CERT_SHA256 = "e0b8dfee9b170b8ea92c1a2691daa6847441080bb9d6d9aa62b827cb152bdd7b"

        /**
         * Pinned SHA-256 certificate fingerprint of the local release upload signing key.
         * Used for local signed testing APKs and Play Console uploads.
         * Derived from: apksigner verify --print-certs -v app/release/app-release.apk
         * Signer #1 certificate SHA-256 digest: df485c640bd322464a72171a516cadf3a3cbb217d0a52111f608a0300c374b66
         */
        const val UPLOAD_RELEASE_CERT_SHA256 = "df485c640bd322464a72171a516cadf3a3cbb217d0a52111f608a0300c374b66"

        /**
         * Backward-compatible alias for the local upload release certificate.
         */
        const val RELEASE_CERT_SHA256 = UPLOAD_RELEASE_CERT_SHA256

        /**
         * Full expected production package and certificate digest bindings.
         */
        const val EXPECTED_PLAY_INTEGRITY_DIGEST = "$EXPECTED_PACKAGE_NAME:$PLAY_APP_SIGNING_CERT_SHA256"
        const val EXPECTED_RELEASE_INTEGRITY_DIGEST = "$EXPECTED_PACKAGE_NAME:$UPLOAD_RELEASE_CERT_SHA256"

        /**
         * Whitelist of trusted release signing certificate fingerprints.
         */
        val TRUSTED_RELEASE_CERT_DIGESTS: Set<String> = setOf(
            PLAY_APP_SIGNING_CERT_SHA256.lowercase(),
            UPLOAD_RELEASE_CERT_SHA256.lowercase()
        )

        /**
         * Fallback digest for unit testing and local debug environments.
         */
        const val DEV_TEST_INTEGRITY_DIGEST = "DEV_TEST_BUILD_DIGEST_GENUINE_REFOCUS"
    }

    // Testing overrides for deterministic multi-platform verification
    internal var packageOverrideForTest: String? = null
    internal var certSha256OverrideForTest: String? = null
    internal var enforceReleaseChecksForTest: Boolean = false

    @Volatile
    private var cachedIntegrityDigest: String? = null

    @Volatile
    private var cachedTamperDetails: String? = null

    /**
     * Clears cached integrity state (used in test cases).
     */
    fun resetCache() {
        cachedIntegrityDigest = null
        cachedTamperDetails = null
    }

    /**
     * Returns the SHA-256 digest string representing the current package identity and signing certificate.
     */
    open fun getIntegrityDigest(): String {
        cachedIntegrityDigest?.let { return it }

        val isDebug = BuildConfig.DEBUG && !enforceReleaseChecksForTest
        val ctx = context
        if ((ctx == null || isDebug) && !enforceReleaseChecksForTest) {
            val digest = DEV_TEST_INTEGRITY_DIGEST
            cachedIntegrityDigest = digest
            return digest
        }

        val pkg = packageOverrideForTest ?: ctx?.packageName ?: EXPECTED_PACKAGE_NAME
        val certSha = certSha256OverrideForTest ?: (ctx?.let { extractCertificateSha256(it) } ?: "")
        val digest = "$pkg:$certSha"
        cachedIntegrityDigest = digest
        return digest
    }

    /**
     * Checks whether the current runtime environment is authentic.
     * In release builds (or when release checks are enforced in testing):
     * 1. Validates exact package name against EXPECTED_PACKAGE_NAME.
     * 2. Validates signing certificate SHA-256 against pinned RELEASE_CERT_SHA256.
     *
     * Fails closed to false on any mismatch or empty certificate.
     */
    open fun isAppIntegrityValid(): Boolean {
        val isDebug = BuildConfig.DEBUG && !enforceReleaseChecksForTest
        if (context == null && !enforceReleaseChecksForTest) {
            return true
        }
        if (isDebug && !enforceReleaseChecksForTest) {
            return true
        }

        val ctx = context
        val currentPackage = packageOverrideForTest ?: ctx?.packageName ?: ""

        // 1. Verify package name
        if (!constantTimeEquals(currentPackage, EXPECTED_PACKAGE_NAME)) {
            cachedTamperDetails = "Package name mismatch: $currentPackage != $EXPECTED_PACKAGE_NAME"
            Log.e(TAG, cachedTamperDetails ?: "")
            return false
        }

        // 2. Verify signing certificate SHA-256 against pinned release certificates
        val certSha256 = certSha256OverrideForTest ?: (ctx?.let { extractCertificateSha256(it) } ?: "")
        if (certSha256.isBlank() || certSha256 == "TAMPERED_EMPTY_CERTIFICATE") {
            cachedTamperDetails = "Invalid or empty signing certificate"
            Log.e(TAG, cachedTamperDetails ?: "")
            return false
        }

        val certShaLower = certSha256.lowercase()
        val isRecognizedCert = constantTimeEquals(certShaLower, PLAY_APP_SIGNING_CERT_SHA256.lowercase()) ||
            constantTimeEquals(certShaLower, UPLOAD_RELEASE_CERT_SHA256.lowercase())

        if (!isRecognizedCert) {
            cachedTamperDetails = "Signing certificate mismatch: $certSha256 is not a recognized release certificate"
            Log.e(TAG, cachedTamperDetails ?: "")
            return false
        }

        return true
    }

    open fun getTamperDetails(): String? = cachedTamperDetails

    /**
     * Extracts SHA-256 digest of the app's signing certificate.
     * Supports Android 9+ (API 28+) SigningInfo with certificate rotation history.
     */
    open fun extractCertificateSha256(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners.firstOrNull()?.toByteArray()
                    } else {
                        val history = signingInfo.signingCertificateHistory
                        if (history != null && history.isNotEmpty()) {
                            history.lastOrNull()?.toByteArray() ?: history.firstOrNull()?.toByteArray()
                        } else {
                            signingInfo.apkContentsSigners.firstOrNull()?.toByteArray()
                        }
                    }
                } else null
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            }

            if (certBytes == null || certBytes.isEmpty()) {
                return "TAMPERED_EMPTY_CERTIFICATE"
            }

            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(certBytes)
            val hex = StringBuilder()
            for (b in hash) {
                hex.append(String.format("%02x", b))
            }
            hex.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error computing runtime integrity digest", e)
            "TAMPERED_EXCEPTION_${e.javaClass.simpleName}"
        }
    }

    /**
     * Constant-time string equality check to prevent side-channel timing attacks.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }
}
