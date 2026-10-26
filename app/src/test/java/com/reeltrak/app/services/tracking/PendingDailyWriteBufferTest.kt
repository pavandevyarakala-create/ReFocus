package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.entity.DailyReelData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDailyWriteBufferTest {
    @Test
    fun failedRoomWriteRemainsPendingAndRetriesSameAbsoluteCount() = runBlocking {
        val buffer = PendingDailyWriteBuffer()
        val row = DailyReelData(dateString = "2026-10-26", totalReelsCount = 8)
        val roomRows = mutableMapOf<String, DailyReelData>()
        var failFirstAttempt = true
        var attempts = 0

        buffer.remember(row)
        val firstFlushComplete = buffer.flush(
            write = {
                attempts++
                if (failFirstAttempt) {
                    failFirstAttempt = false
                    throw IllegalStateException("simulated Room write failure")
                }
                roomRows[it.dateString] = it
            },
            onFailure = { _, _ -> }
        )

        assertFalse(firstFlushComplete)
        assertEquals(row, buffer.rowFor(row.dateString))

        val retryComplete = buffer.flush(
            write = {
                attempts++
                roomRows[it.dateString] = it
            },
            onFailure = { _, _ -> }
        )

        assertTrue(retryComplete)
        assertEquals(2, attempts)
        assertEquals(8, roomRows[row.dateString]?.totalReelsCount)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun newerRowReplacesPendingRowPerDateWhileAnotherDateRemainsPending() = runBlocking {
        val buffer = PendingDailyWriteBuffer()
        val yesterday = DailyReelData(dateString = "2026-10-25", totalReelsCount = 4)
        val todayFirst = DailyReelData(dateString = "2026-10-26", totalReelsCount = 1)
        val todayLatest = todayFirst.copy(totalReelsCount = 2)
        buffer.remember(yesterday)
        buffer.remember(todayFirst)
        buffer.remember(todayLatest)

        assertEquals(yesterday, buffer.rowFor(yesterday.dateString))
        assertEquals(todayLatest, buffer.rowFor(todayLatest.dateString))

        var yesterdayFailures = 0
        val roomRows = mutableMapOf<String, DailyReelData>()
        val complete = buffer.flush(
            write = {
                if (it.dateString == yesterday.dateString) {
                    yesterdayFailures++
                    throw IllegalStateException("historical write unavailable")
                }
                roomRows[it.dateString] = it
            },
            onFailure = { _, _ -> }
        )

        assertFalse(complete)
        assertEquals(1, yesterdayFailures)
        assertEquals(2, roomRows[todayLatest.dateString]?.totalReelsCount)
        assertEquals(yesterday, buffer.rowFor(yesterday.dateString))
        assertEquals(null, buffer.rowFor(todayLatest.dateString))
    }

    @Test
    fun cancellationLeavesAbsoluteRowPendingForLaterRecovery() = runBlocking {
        val buffer = PendingDailyWriteBuffer()
        val row = DailyReelData(dateString = "2026-10-26", totalReelsCount = 3)
        buffer.remember(row)

        var cancellationObserved = false
        try {
            buffer.flush(
                write = { throw CancellationException("simulated cancelled Room write") },
                onFailure = { _, _ -> }
            )
        } catch (_: CancellationException) {
            cancellationObserved = true
        } finally {
            assertEquals(row, buffer.rowFor(row.dateString))
        }
        assertTrue(cancellationObserved)
    }
}
