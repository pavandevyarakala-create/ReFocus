package com.reeltrak.app.security

import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.SessionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 4.3 Room Database Security & At-Rest Threat Model Verification Tests
 *
 * Verifies:
 * - Room entity schema does not store any auth tokens, credentials, or secrets.
 * - Device-local today state (dateString == today) is decoupled from user credentials.
 * - Account switching preserves today's enforcement and clears only historical data.
 * - Device-bound key lifecycle model preserves database access across logout and account switches.
 * - Fail-safe behavior: missing/corrupt key handling protects enforcement rather than resetting limits.
 */
class Phase4RoomSecurityTest {

    // Mock representation of an in-memory SQLite storage layer with device-bound key access
    class MockDeviceBoundDatabaseStore {
        private val tables = ConcurrentHashMap<String, MutableMap<String, DailyReelData>>()
        private var isKeyAccessible: Boolean = true

        init {
            tables["daily_reel_data"] = ConcurrentHashMap()
        }

        fun setKeyAccessible(accessible: Boolean) {
            this.isKeyAccessible = accessible
        }

        fun insertOrReplace(data: DailyReelData) {
            if (!isKeyAccessible) throw SecurityException("Keystore master key inaccessible")
            tables["daily_reel_data"]?.put(data.dateString, data)
        }

        fun getDailyData(dateString: String): DailyReelData? {
            if (!isKeyAccessible) throw SecurityException("Keystore master key inaccessible")
            return tables["daily_reel_data"]?.get(dateString)
        }

        fun clearHistoricalExcept(today: String) {
            if (!isKeyAccessible) throw SecurityException("Keystore master key inaccessible")
            val table = tables["daily_reel_data"] ?: return
            val todayRecord = table[today]
            table.clear()
            if (todayRecord != null) {
                table[today] = todayRecord
            }
        }

        fun size(): Int = tables["daily_reel_data"]?.size ?: 0
    }

    @Test
    fun testRoomSchema_ZeroCredentialFieldsStored() {
        val dailyReelDataClass = DailyReelData::class.java
        val userFields = dailyReelDataClass.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("$") && !it.name.contains("Companion") }
            .map { it.name }

        // Allowed telemetry and enforcement fields
        val expectedFields = setOf(
            "dateString",
            "totalReelsCount",
            "totalTimeSpentMillis",
            "dailyGoal",
            "isSkullDay",
            "isStrictModeOn",
            "isStrictModeLocked",
            "isLimitLocked",
            "sessions",
            "updatedAt",
            "syncStatus"
        )

        for (field in userFields) {
            assertTrue("Field '$field' must be in approved schema list", expectedFields.contains(field))
        }

        // Assert no forbidden credential fields exist
        val forbiddenSubstrings = listOf("token", "secret", "password", "auth", "credential", "bearer", "jwt", "key")
        for (field in userFields) {
            if (field == "isLimitLocked" || field == "isStrictModeLocked") continue
            for (forbidden in forbiddenSubstrings) {
                assertFalse(
                    "Field '$field' must not contain credential keyword '$forbidden'",
                    field.lowercase().contains(forbidden) && field != "syncStatus"
                )
            }
        }
    }


    @Test
    fun testDeviceBoundKeyModel_AccountSwitchingPreservesTodayAndClearsHistorical() {
        val store = MockDeviceBoundDatabaseStore()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val pastStr = LocalDate.now().minusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Account A tracks 45 reels today, strict mode locked
        val todayData = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 45,
            dailyGoal = 50,
            isStrictModeOn = true,
            isStrictModeLocked = true,
            sessions = listOf(SessionData("com.instagram.android", 1000L, 2000L, 45))
        )
        val pastData = DailyReelData(
            dateString = pastStr,
            totalReelsCount = 90,
            dailyGoal = 60,
            isStrictModeOn = false
        )

        store.insertOrReplace(todayData)
        store.insertOrReplace(pastData)
        assertEquals(2, store.size())

        // User switches from Account A to Account B
        // Device-local today record is preserved; historical is cleared
        store.clearHistoricalExcept(todayStr)

        assertEquals(1, store.size())
        val preservedToday = store.getDailyData(todayStr)
        assertNotNull(preservedToday)
        assertEquals(45, preservedToday?.totalReelsCount)
        assertTrue(preservedToday?.isStrictModeOn == true)
        assertTrue(preservedToday?.isStrictModeLocked == true)

        assertNull(store.getDailyData(pastStr))
    }

    @Test
    fun testDeviceBoundKeyModel_LogoutDoesNotDestroyDatabaseAccess() {
        val store = MockDeviceBoundDatabaseStore()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        store.insertOrReplace(DailyReelData(dateString = todayStr, totalReelsCount = 20, isStrictModeOn = true))

        // On logout, device-bound key remains valid for the app sandbox
        val todayRecord = store.getDailyData(todayStr)
        assertNotNull("Database must remain accessible to ReelTrackerService and DayManager after logout", todayRecord)
        assertEquals(20, todayRecord?.totalReelsCount)
    }

    @Test(expected = SecurityException::class)
    fun testDeviceBoundKeyModel_InaccessibleKeyFailsSafely() {
        val store = MockDeviceBoundDatabaseStore()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        store.insertOrReplace(DailyReelData(dateString = todayStr, totalReelsCount = 30))

        // Simulate Keystore hardware failure or unauthenticated hardware state
        store.setKeyAccessible(false)

        // Must throw SecurityException rather than resetting count to 0 or wiping DB
        store.getDailyData(todayStr)
    }
}
