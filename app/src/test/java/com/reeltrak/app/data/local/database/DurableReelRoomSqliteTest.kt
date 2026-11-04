package com.reeltrak.app.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.reeltrak.app.core.di.DatabaseModule
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.ActiveAccountScope
import com.reeltrak.app.data.local.entity.ReelCountAccountScope
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.TransitionAcceptance
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DurableReelRoomSqliteTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun openDatabase(
        name: String,
        callback: RoomDatabase.QueryCallback? = null
    ): ReelTrakDatabase {
        val builder = Room.databaseBuilder(context, ReelTrakDatabase::class.java, name)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
        if (callback != null) builder.setQueryCallback(callback, Executor { command -> command.run() })
        return builder.build()
    }

    private fun seed(database: ReelTrakDatabase, date: String = "2026-10-26", count: Int = 0) = runBlocking {
        database.dailyReelDataDao.insertOrReplace(
            DailyReelData(
                dateString = date,
                totalReelsCount = count,
                totalTimeSpentMillis = 123_456L,
                dailyGoal = 75,
                isSkullDay = true,
                isStrictModeOn = false,
                isStrictModeLocked = true,
                isLimitLocked = true,
                sessions = emptyList(),
                updatedAt = 77_777L,
                syncStatus = 1
            )
        )
    }

    private fun fingerprint(value: String) = SemanticFingerprint(
        primaryIdentifier = "creator-$value",
        secondaryIdentifier = "caption-$value",
        auxiliaryIdentifier = "audio-$value",
        platform = "Instagram"
    )

    private fun snapshot(fp: SemanticFingerprint, timestamp: Long = 100L) = DetectionSnapshot(
        timestamp = timestamp,
        classification = SnapshotClassification.COMMITTED,
        fingerprint = fp,
        platform = fp.platform
    )

    @Test
    fun `real version 12 data is retained under local account scope and accessible to DAOs`() {
        val name = "migration-${System.nanoTime()}.db"
        val helper = Version12DatabaseHelper(context, name)
        helper.writableDatabase.use { oldDb ->
            oldDb.execSQL(
                "INSERT INTO daily_reel_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("2026-10-26", 17, 98765L, 80, 1, 0, 1, 1, "[]", 456789L, 1)
            )
        }
        helper.close()

        val migrated = openDatabase(name)
        val row = runBlocking { migrated.dailyReelDataDao.getDailyDataSync("2026-10-26") }
        assertNotNull("Legacy record must be accessible under local account scope", row)
        assertEquals("local", row?.accountScope)
        assertEquals(17, row?.totalReelsCount)
        assertEquals(98765L, row?.totalTimeSpentMillis)
        assertEquals(80, row?.dailyGoal)
        migrated.openHelper.readableDatabase.query(
            "SELECT dateString, totalReelsCount, totalTimeSpentMillis, dailyGoal, isSkullDay, isStrictModeOn, " +
                "isStrictModeLocked, isLimitLocked, sessions, updatedAt, syncStatus FROM daily_reel_data " +
                "WHERE account_scope = 'local'"
        ).use { cursor ->
            assertTrue("Legacy record should remain stored for possible owner recovery", cursor.moveToFirst())
            assertEquals("2026-10-26", cursor.getString(0))
            assertEquals(17, cursor.getInt(1))
            assertEquals(98765L, cursor.getLong(2))
            assertEquals(80, cursor.getInt(3))
            assertEquals(1, cursor.getInt(4))
            assertEquals(0, cursor.getInt(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals(1, cursor.getInt(7))
            assertEquals("[]", cursor.getString(8))
            assertEquals(456789L, cursor.getLong(9))
            assertEquals(1, cursor.getInt(10))
        }

        val sqlite = migrated.openHelper.writableDatabase
        val columns = mutableMapOf<String, Int>()
        sqlite.query("PRAGMA table_info(accepted_reel_transition_state)").use { cursor ->
            while (cursor.moveToNext()) {
                columns[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                    cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
            }
        }
        assertEquals(
            mapOf(
                "account_scope" to 1,
                "date_string" to 2,
                "platform" to 0,
                "primary_identifier" to 0,
                "secondary_identifier" to 0,
                "auxiliary_identifier" to 0,
                "updated_at_ms" to 0
            ),
            columns
        )
        sqlite.query("PRAGMA index_list(accepted_reel_transition_state)").use { cursor ->
            assertTrue("SQLite must create the composite primary-key index", cursor.moveToFirst())
            val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            sqlite.query("PRAGMA index_info(`$indexName`)").use { indexCursor ->
                val indexColumns = mutableListOf<String>()
                while (indexCursor.moveToNext()) indexColumns += indexCursor.getString(indexCursor.getColumnIndexOrThrow("name"))
                assertEquals(listOf("account_scope", "date_string"), indexColumns)
            }
        }
        migrated.close()

        val reopened = openDatabase(name)
        val reopenedRow = runBlocking { reopened.dailyReelDataDao.getDailyDataSync("2026-10-26") }
        assertNotNull(reopenedRow)
        assertEquals(17, reopenedRow?.totalReelsCount)
        assertEquals("local", reopenedRow?.accountScope)
        val legacyRows = reopened.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM daily_reel_data WHERE account_scope = 'local'"
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        assertEquals(1, legacyRows)
        reopened.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `version 5 daily_statistics data is migrated to daily_reel_data under local scope`() {
        val name = "migration-v5-${System.nanoTime()}.db"
        val helper = Version5DatabaseHelper(context, name)
        helper.writableDatabase.use { oldDb ->
            // dateEpochDay 20680 corresponds to 2026-08-15
            oldDb.execSQL(
                "INSERT INTO daily_statistics VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(20680L, 42, 60, 0.7f, 2, 123456789L)
            )
            oldDb.execSQL(
                "INSERT INTO scroll_sessions VALUES (?, ?, ?, ?, ?)",
                arrayOf(1, "com.instagram.android", 1000L, 2000L, 5)
            )
            oldDb.execSQL(
                "INSERT INTO global_statistics VALUES (?, ?, ?)",
                arrayOf(1, 42, 1)
            )
        }
        helper.close()

        val migrated = openDatabase(name)
        val row = runBlocking { migrated.dailyReelDataDao.getDailyDataSync("2026-08-15") }
        assertNotNull("Version 5 daily_statistics must be converted into daily_reel_data", row)
        assertEquals("local", row?.accountScope)
        assertEquals("2026-08-15", row?.dateString)
        assertEquals(42, row?.totalReelsCount)
        assertEquals(60, row?.dailyGoal)
        assertEquals(123456789L, row?.updatedAt)
        migrated.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `version 10 daily_reel_data is migrated under local scope with all columns intact`() {
        val name = "migration-v10-${System.nanoTime()}.db"
        val helper = Version10DatabaseHelper(context, name)
        helper.writableDatabase.use { oldDb ->
            oldDb.execSQL(
                "INSERT INTO daily_reel_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("2026-10-20", 33, 54321L, 50, 0, 1, 1, 1, "[]", 888888L)
            )
        }
        helper.close()

        val migrated = openDatabase(name)
        val row = runBlocking { migrated.dailyReelDataDao.getDailyDataSync("2026-10-20") }
        assertNotNull("Version 10 record must be migrated and accessible under local scope", row)
        assertEquals("local", row?.accountScope)
        assertEquals("2026-10-20", row?.dateString)
        assertEquals(33, row?.totalReelsCount)
        assertEquals(54321L, row?.totalTimeSpentMillis)
        assertEquals(50, row?.dailyGoal)
        assertEquals(true, row?.isStrictModeOn)
        assertEquals(true, row?.isStrictModeLocked)
        assertEquals(true, row?.isLimitLocked)
        assertEquals(888888L, row?.updatedAt)
        migrated.close()
        context.deleteDatabase(name)
        context.deleteDatabase(name)
    }

    @Test
    fun `account scopes persist across switching and recreation`() = runBlocking {
        val name = "account-scope-${System.nanoTime()}.db"
        var database = openDatabase(name)
        val dao = database.dailyReelDataDao
        val today = "2026-10-26"
        val scopeA = ReelCountAccountScope.fromUid("account-a")
        val scopeB = ReelCountAccountScope.fromUid("account-b")

        suspend fun activate(scope: String) {
            database.activeAccountScopeDao.setActiveScope(ActiveAccountScope(accountScope = scope))
            ReelCountAccountScope.setCurrent(scope)
        }

        try {
            activate(scopeA)
            dao.insertOrReplace(
                DailyReelData(dateString = today, totalReelsCount = 17, dailyGoal = 60, isStrictModeOn = true)
            )
            assertEquals(17, dao.getDailyDataSync(today)?.totalReelsCount)

            activate(scopeB)
            assertNull("Account B must not read Account A's same-day count", dao.getDailyDataSync(today))
            assertTrue(dao.getAllDailyDataFlow().first().isEmpty())
            dao.insertOrReplace(
                DailyReelData(dateString = today, totalReelsCount = 4, dailyGoal = 25, isStrictModeOn = false)
            )
            assertEquals(4, dao.getDailyDataSync(today)?.totalReelsCount)
            assertEquals(25, dao.getDailyDataRangeFlow(today, today).first().single().dailyGoal)

            // Logout selects an isolated local-only partition and preserves both account partitions.
            activate("local")
            assertNull(dao.getDailyDataSync(today))
            assertTrue(dao.getAllDailyDataSync().isEmpty())
            activate(scopeA)
            assertEquals(60, dao.getDailyDataSync(today)?.dailyGoal)
            assertTrue(dao.getDailyDataSync(today)?.isStrictModeOn == true)

            // Simulate an account-scoped cloud restore for A and prove it stays hidden from B.
            dao.insertAll(listOf(DailyReelData(dateString = "2026-10-25", totalReelsCount = 99, syncStatus = 0)))
            assertEquals(99, dao.getDailyDataSync("2026-10-25")?.totalReelsCount)
            activate(scopeB)
            assertNull(dao.getDailyDataSync("2026-10-25"))
            assertEquals(4, dao.getDailyDataSync(today)?.totalReelsCount)

            database.close()
            database = openDatabase(name)
            val reopenedDao = database.dailyReelDataDao
            val persistedScope = database.activeAccountScopeDao.getActiveScope()
            assertEquals(scopeB, persistedScope)
            ReelCountAccountScope.setCurrent(persistedScope!!)
            assertEquals(4, reopenedDao.getDailyDataSync(today)?.totalReelsCount)
            activate(scopeA)
            assertEquals(17, reopenedDao.getDailyDataSync(today)?.totalReelsCount)
            assertEquals(99, reopenedDao.getDailyDataSync("2026-10-25")?.totalReelsCount)
        } finally {
            database.close()
            context.deleteDatabase(name)
            ReelCountAccountScope.setCurrent("local")
        }
    }

    @Test
    fun `marker insert failure rolls back the real Room count transaction and retry succeeds once`() = runBlocking {
        val name = "atomic-${System.nanoTime()}.db"
        ReelCountAccountScope.setCurrent("local")
        val database = openDatabase(name)
        seed(database)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER reject_marker BEFORE INSERT ON accepted_reel_transition_state " +
                "BEGIN SELECT RAISE(ABORT, 'forced marker failure'); END"
        )
        val dao = database.dailyReelDataDao
        val account = "local"
        val fp = fingerprint("atomic")
        val ledger = TransitionLedger(
            onIncrement = {},
            onHistoricalMatch = null,
            historyStore = null,
            transitionAcceptance = {
                val result = try {
                    runBlocking(Dispatchers.IO) {
                        dao.acceptReelTransition("2026-10-26", account, fp, 100L)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                when (result?.status) {
                    com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.COUNTED -> TransitionAcceptance.ACCEPTED
                    com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.ALREADY_ACCEPTED -> TransitionAcceptance.ALREADY_ACCEPTED
                    else -> TransitionAcceptance.REJECTED
                }
            }
        )

        assertTrue("marker insert failure must leave ledger transition available", !ledger.process(snapshot(fp)))
        assertNull(ledger.currentActiveFingerprint)
        assertEquals(0, dao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        assertNull(dao.getAcceptedTransitionState(account, "2026-10-26"))

        sqlite.execSQL("DROP TRIGGER reject_marker")
        assertTrue("same snapshot should be eligible after Room rejection", ledger.process(snapshot(fp, 200L)))
        assertEquals(fp, ledger.currentActiveFingerprint)
        assertEquals(1, dao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        assertEquals(fp, dao.getAcceptedTransitionState(account, "2026-10-26")?.let {
            SemanticFingerprint(it.primaryIdentifier, it.secondaryIdentifier, it.auxiliaryIdentifier, it.platform)
        })
        assertTrue(ledger.process(snapshot(fp, 300L)))
        assertEquals(1, dao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        database.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun `real Room transaction rolls back after coroutine cancellation during count update`() = runBlocking {
        val name = "cancel-${System.nanoTime()}.db"
        ReelCountAccountScope.setCurrent("local")
        val updateStarted = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val callback = RoomDatabase.QueryCallback { sql, _ ->
            if (sql.contains("UPDATE daily_reel_data")) {
                updateStarted.countDown()
                check(releaseUpdate.await(10, TimeUnit.SECONDS))
            }
        }
        val database = openDatabase(name, callback)
        seed(database)
        val account = "local"
        val fp = fingerprint("cancel")
        val job = async(Dispatchers.IO) {
            database.dailyReelDataDao.acceptReelTransition("2026-10-26", account, fp, 100L)
        }

        assertTrue(withContext(Dispatchers.IO) { updateStarted.await(5, TimeUnit.SECONDS) })
        job.cancel()
        releaseUpdate.countDown()
        job.cancelAndJoin()

        val dao = database.dailyReelDataDao
        val countAfterCancellation = dao.getDailyDataSync("2026-10-26")?.totalReelsCount
        val markerAfterCancellation = dao.getAcceptedTransitionState(account, "2026-10-26")
        if (countAfterCancellation == 0) {
            assertNull("rollback must remove the replay marker too", markerAfterCancellation)
        } else {
            assertEquals("a completed transaction must commit exactly one count", 1, countAfterCancellation)
            assertNotNull("a committed count must have its replay marker", markerAfterCancellation)
            assertEquals(
                com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.ALREADY_ACCEPTED,
                dao.acceptReelTransition("2026-10-26", account, fp, 200L).status
            )
            assertEquals(1, dao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        }
        database.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun `same transition concurrently commits once while distinct transitions are not lost`() = runBlocking {
        val name = "concurrent-${System.nanoTime()}.db"
        ReelCountAccountScope.setCurrent("local")
        val database = openDatabase(name)
        seed(database)
        val dao = database.dailyReelDataDao
        val duplicate = fingerprint("same")
        val duplicateResults = (1..24).map {
            async(Dispatchers.IO) { dao.acceptReelTransition("2026-10-26", "local", duplicate, it.toLong()) }
        }.awaitAll()
        assertEquals(1, duplicateResults.count { it.status == com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.COUNTED })
        assertEquals(23, duplicateResults.count { it.status == com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.ALREADY_ACCEPTED })

        val distinctResults = (1..16).map { index ->
            async(Dispatchers.IO) { dao.acceptReelTransition("2026-10-26", "local", fingerprint("unique-$index"), index.toLong()) }
        }.awaitAll()
        assertTrue(distinctResults.all { it.status == com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.COUNTED })
        assertEquals(17, dao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        assertEquals(75, dao.getDailyDataSync("2026-10-26")?.dailyGoal)
        database.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun `marker is scoped to account and date and survives disk reopen and local restore merge`() = runBlocking {
        val name = "reopen-${System.nanoTime()}.db"
        ReelCountAccountScope.setCurrent("local")
        var database = openDatabase(name)
        val scopeA = "local"
        val scopeB = "account-b"
        seed(database, "2026-10-26")
        seed(database, "2026-10-27")
        val dao = database.dailyReelDataDao
        val fp = fingerprint("scope")
        assertEquals(com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.COUNTED,
            dao.acceptReelTransition("2026-10-26", scopeA, fp, 1L).status)

        // Mirrors the restore policy: when today's local tracked row has a count, local wins.
        database.withTransaction {
            val local = dao.getDailyDataSync("2026-10-26")
            if (local == null || local.totalReelsCount == 0) {
                dao.insertOrReplace(local?.copy(totalReelsCount = 99) ?: DailyReelData(dateString = "2026-10-26", totalReelsCount = 99))
            }
        }
        assertEquals(com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.ALREADY_ACCEPTED,
            dao.acceptReelTransition("2026-10-26", scopeA, fp, 2L).status)
        database.activeAccountScopeDao.setActiveScope(ActiveAccountScope(accountScope = scopeB))
        ReelCountAccountScope.setCurrent(scopeB)
        dao.insertOrReplace(DailyReelData(accountScope = scopeB, dateString = "2026-10-26"))
        assertEquals(com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.COUNTED,
            dao.acceptReelTransition("2026-10-26", scopeB, fp, 3L).status)
        database.activeAccountScopeDao.setActiveScope(ActiveAccountScope(accountScope = scopeA))
        ReelCountAccountScope.setCurrent(scopeA)
        assertEquals(com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.COUNTED,
            dao.acceptReelTransition("2026-10-27", scopeA, fp, 4L).status)
        assertEquals(1, dao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        assertEquals(1, dao.getDailyDataSync("2026-10-27")?.totalReelsCount)
        database.close()

        database = openDatabase(name)
        val reopenedDao = database.dailyReelDataDao
        assertEquals(1, reopenedDao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        var committedAcceptance: TransitionAcceptance? = null
        val resumedLedger = TransitionLedger(
            onIncrement = {},
            onHistoricalMatch = null,
            historyStore = null,
            transitionAcceptance = {
                when (runBlocking(Dispatchers.IO) {
                    reopenedDao.acceptReelTransition("2026-10-26", scopeA, fp, 5L).status
                }) {
                    com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.COUNTED -> TransitionAcceptance.ACCEPTED
                    com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.ALREADY_ACCEPTED -> TransitionAcceptance.ALREADY_ACCEPTED
                    com.reeltrak.app.data.local.dao.ReelTransitionWriteStatus.DAILY_ROW_MISSING -> TransitionAcceptance.REJECTED
                }
            },
            onTransitionCommitted = { committedAcceptance = it }
        )
        assertTrue(resumedLedger.process(snapshot(fp)))
        assertEquals(TransitionAcceptance.ALREADY_ACCEPTED, committedAcceptance)
        assertEquals(1, reopenedDao.getDailyDataSync("2026-10-26")?.totalReelsCount)
        database.close()
        context.deleteDatabase(name)
        Unit
    }

    private class Version12DatabaseHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 12) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE daily_reel_data (dateString TEXT NOT NULL, totalReelsCount INTEGER NOT NULL, " +
                    "totalTimeSpentMillis INTEGER NOT NULL, dailyGoal INTEGER NOT NULL, isSkullDay INTEGER NOT NULL, " +
                    "isStrictModeOn INTEGER NOT NULL, isStrictModeLocked INTEGER NOT NULL, isLimitLocked INTEGER NOT NULL, " +
                    "sessions TEXT NOT NULL, updatedAt INTEGER NOT NULL, syncStatus INTEGER NOT NULL, PRIMARY KEY(dateString))"
            )
            db.execSQL(
                "CREATE TABLE local_restore_journal (idempotency_key TEXT NOT NULL, backup_id TEXT NOT NULL, " +
                    "session_date_ist TEXT NOT NULL, applied_at_ms INTEGER NOT NULL, records_restored INTEGER NOT NULL, " +
                    "restored_daily_limit INTEGER, ack_status TEXT NOT NULL, datastore_synced INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(idempotency_key))"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private class Version5DatabaseHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 5) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `daily_statistics` (" +
                    "`dateEpochDay` INTEGER NOT NULL, " +
                    "`totalReelsScrolled` INTEGER NOT NULL, " +
                    "`dailyGoal` INTEGER NOT NULL, " +
                    "`completionPercentage` REAL NOT NULL, " +
                    "`colorLevel` INTEGER NOT NULL, " +
                    "`lastUpdated` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`dateEpochDay`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scroll_sessions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "`appName` TEXT NOT NULL, " +
                    "`startTimeStamp` INTEGER NOT NULL, " +
                    "`endTimeStamp` INTEGER NOT NULL, " +
                    "`reelsScrolled` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `global_statistics` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`lifetimeReels` INTEGER NOT NULL, " +
                    "`completedDays` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private class Version10DatabaseHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 10) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE daily_reel_data (dateString TEXT NOT NULL, totalReelsCount INTEGER NOT NULL, " +
                    "totalTimeSpentMillis INTEGER NOT NULL, dailyGoal INTEGER NOT NULL, isSkullDay INTEGER NOT NULL, " +
                    "isStrictModeOn INTEGER NOT NULL, isStrictModeLocked INTEGER NOT NULL, isLimitLocked INTEGER NOT NULL, " +
                    "sessions TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(dateString))"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

}
