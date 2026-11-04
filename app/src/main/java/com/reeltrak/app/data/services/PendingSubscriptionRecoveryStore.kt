package com.reeltrak.app.data.services

import android.content.Context
import java.security.MessageDigest

/** Stores only a Razorpay subscription reference; entitlement is always reconciled by the Worker. */
internal class PendingSubscriptionRecoveryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "pending_subscription_recovery",
        Context.MODE_PRIVATE
    )

    fun save(uid: String, subscriptionId: String): Boolean {
        if (uid.isBlank() || !subscriptionId.startsWith("sub_")) return false
        return preferences.edit().putString(keyFor(uid), subscriptionId).commit()
    }

    fun load(uid: String): String? {
        if (uid.isBlank()) return null
        return preferences.getString(keyFor(uid), null)?.takeIf { it.startsWith("sub_") }
    }

    fun clear(uid: String): Boolean {
        if (uid.isBlank()) return false
        return preferences.edit().remove(keyFor(uid)).commit()
    }

    private fun keyFor(uid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        val uidHash = digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "subscription_$uidHash"
    }
}
