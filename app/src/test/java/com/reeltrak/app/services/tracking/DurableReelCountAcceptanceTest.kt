package com.reeltrak.app.services.tracking

import androidx.sqlite.db.SupportSQLiteDatabase
import com.reeltrak.app.core.di.DatabaseModule
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.TransitionAcceptance
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DurableReelCountAcceptanceTest {
    private fun source(relativePath: String) = listOf(
        File(relativePath), File("app/$relativePath"), File("../app/$relativePath")
    ).firstOrNull { it.exists() }?.readText()?.replace("\r\n", "\n")
        ?: error("Source file not found: $relativePath")

    private data class DayRow(val count: Int, val dailyGoal: Int)
    private data class Key(val account: String, val date: String)
    private enum class WriteResult { COUNTED, ALREADY_ACCEPTED, REJECTED }
    private enum class FailurePoint { NONE, BEFORE_WRITE, AFTER_COUNT_BEFORE_MARKER }

    /** In-memory transactional model shared between manager instances to model Room across restart. */
    private class DurableStore {
        private val rows = mutableMapOf<String, DayRow>()
        private val accepted = mutableMapOf<Key, SemanticFingerprint>()

        @Synchronized
        fun seed(date: String, count: Int = 0, goal: Int = 40) {
            rows[date] = DayRow(count, goal)
        }

        @Synchronized
        fun accept(
            account: String,
            date: String,
            fingerprint: SemanticFingerprint,
            failure: FailurePoint = FailurePoint.NONE
        ): WriteResult {
            val key = Key(account, date)
            val row = rows[date] ?: return WriteResult.REJECTED
            if (accepted[key]?.isSameAs(fingerprint) == true) return WriteResult.ALREADY_ACCEPTED
            if (failure == FailurePoint.BEFORE_WRITE) return WriteResult.REJECTED

            val oldMarker = accepted[key]
            try {
                rows[date] = row.copy(count = row.count + 1)
                if (failure == FailurePoint.AFTER_COUNT_BEFORE_MARKER) {
                    error("simulated marker write failure")
                }
                accepted[key] = fingerprint
                return WriteResult.COUNTED
            } catch (_: Exception) {
                // Room's @Transaction rolls the count update back with a failed marker insert.
                rows[date] = row
                if (oldMarker == null) accepted.remove(key) else accepted[key] = oldMarker
                return WriteResult.REJECTED
            }
        }

        @Synchronized fun count(date: String) = rows[date]?.count
        @Synchronized fun goal(date: String) = rows[date]?.dailyGoal
    }

    private fun snapshot(fp: SemanticFingerprint, timestamp: Long = 1L) = DetectionSnapshot(
        timestamp = timestamp,
        classification = SnapshotClassification.COMMITTED,
        fingerprint = fp,
        platform = fp.platform
    )

    @Test
    fun `failures before or during durable write leave transition eligible for retry`() {
        val store = DurableStore().apply { seed("2026-10-26") }
        val fp = SemanticFingerprint("creator", "caption", "audio", "Instagram")

        assertEquals(WriteResult.REJECTED, store.accept("acct", "2026-10-26", fp, FailurePoint.BEFORE_WRITE))
        assertEquals(0, store.count("2026-10-26"))
        assertEquals(WriteResult.REJECTED, store.accept("acct", "2026-10-26", fp, FailurePoint.AFTER_COUNT_BEFORE_MARKER))
        assertEquals("failed transaction must roll back count", 0, store.count("2026-10-26"))

        assertEquals(WriteResult.COUNTED, store.accept("acct", "2026-10-26", fp))
        assertEquals(1, store.count("2026-10-26"))
    }

    @Test
    fun `same transition after manager restart is recognized without a second count`() {
        val store = DurableStore().apply { seed("2026-10-26") }
        val fp = SemanticFingerprint("creator", "caption", "audio", "Instagram")
        assertEquals(WriteResult.COUNTED, store.accept("acct", "2026-10-26", fp))

        // A fresh ledger models process restart: only the durable count and marker survive.
        val resumedLedger = TransitionLedger(
            onIncrement = {},
            onHistoricalMatch = null,
            historyStore = null,
            transitionAcceptance = {
                when (store.accept("acct", "2026-10-26", fp)) {
                    WriteResult.COUNTED -> TransitionAcceptance.ACCEPTED
                    WriteResult.ALREADY_ACCEPTED -> TransitionAcceptance.ALREADY_ACCEPTED
                    WriteResult.REJECTED -> TransitionAcceptance.REJECTED
                }
            }
        )

        assertTrue(resumedLedger.process(snapshot(fp)))
        assertEquals(1, store.count("2026-10-26"))
    }

    @Test
    fun `concurrent duplicate delivery is counted once and preserves daily settings`() {
        val store = DurableStore().apply { seed("2026-10-26", goal = 75) }
        val fp = SemanticFingerprint("creator", "caption", "audio", "Instagram")
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..32).map {
            pool.submit<WriteResult> {
                start.await()
                store.accept("acct", "2026-10-26", fp)
            }
        }
        start.countDown()
        val values = results.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, values.count { it == WriteResult.COUNTED })
        assertEquals(31, values.count { it == WriteResult.ALREADY_ACCEPTED })
        assertEquals(1, store.count("2026-10-26"))
        assertEquals(75, store.goal("2026-10-26"))
    }

    @Test
    fun `account and date scopes remain independent`() {
        val store = DurableStore().apply {
            seed("2026-10-26")
            seed("2026-10-27")
        }
        val fp = SemanticFingerprint("creator", "caption", "audio", "Instagram")
        assertEquals(WriteResult.COUNTED, store.accept("acct-a", "2026-10-26", fp))
        assertEquals(WriteResult.COUNTED, store.accept("acct-b", "2026-10-26", fp))
        assertEquals(WriteResult.COUNTED, store.accept("acct-a", "2026-10-27", fp))
        assertEquals(WriteResult.ALREADY_ACCEPTED, store.accept("acct-a", "2026-10-26", fp))
        assertEquals(3, store.count("2026-10-26")!! + store.count("2026-10-27")!!)
    }

    @Test
    fun `Room transaction changes count columns and atomically records replay marker`() {
        val dao = source("src/main/java/com/reeltrak/app/data/local/dao/DailyReelDataDao.kt")
        assertTrue(dao.contains("@Transaction\n    suspend fun acceptReelTransition("))
        assertTrue(dao.contains("suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int"))
        assertTrue(dao.indexOf("incrementDailyReelCount(dateString, acceptedAtMs)") < dao.indexOf("AcceptedReelTransitionState.from("))
        assertTrue(dao.contains("getAcceptedTransitionState(accountScope, dateString)"))
        assertFalse(dao.contains("insertOrReplace(updated)"))
    }

    @Test
    fun `version 12 to 13 migration creates only the durable marker table`() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase

        DatabaseModule.MIGRATION_12_13.migrate(database)
        assertEquals(1, statements.size)
        assertTrue(statements.single().contains("CREATE TABLE IF NOT EXISTS accepted_reel_transition_state"))
        assertTrue(statements.single().contains("PRIMARY KEY(account_scope, date_string)"))
        assertTrue(statements.single().contains("updated_at_ms INTEGER NOT NULL"))
    }
}
