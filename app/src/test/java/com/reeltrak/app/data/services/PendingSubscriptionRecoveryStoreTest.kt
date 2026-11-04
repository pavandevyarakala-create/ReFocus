package com.reeltrak.app.data.services

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingSubscriptionRecoveryStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun pendingSubscriptionSurvivesStoreRecreationAndIsScopedToItsAccount() {
        val uid = "recovery-${UUID.randomUUID()}"
        val otherUid = "$uid-other"
        val subscriptionId = "sub_recovery_test"
        val firstStore = PendingSubscriptionRecoveryStore(context)

        assertTrue(firstStore.save(uid, subscriptionId))
        assertEquals(subscriptionId, PendingSubscriptionRecoveryStore(context).load(uid))
        assertNull(firstStore.load(otherUid))
    }

    @Test
    fun pendingSubscriptionCanBeClearedAfterServerReconciliation() {
        val uid = "recovery-${UUID.randomUUID()}"
        val store = PendingSubscriptionRecoveryStore(context)
        assertTrue(store.save(uid, "sub_reconciled_test"))

        assertTrue(PendingSubscriptionRecoveryStore(context).clear(uid))
        assertNull(store.load(uid))
    }
}
