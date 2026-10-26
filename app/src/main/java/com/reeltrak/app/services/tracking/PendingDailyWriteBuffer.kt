package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.entity.DailyReelData
import kotlinx.coroutines.CancellationException

/**
 * Keeps the latest absolute Room row for each date until its write completes.
 * Replaying these rows is idempotent because the DAO uses REPLACE by date key.
 */
internal class PendingDailyWriteBuffer {
    private val rowsByDate = linkedMapOf<String, DailyReelData>()

    val isEmpty: Boolean
        get() = rowsByDate.isEmpty()

    fun remember(row: DailyReelData) {
        rowsByDate[row.dateString] = row
    }

    fun rowFor(dateString: String): DailyReelData? = rowsByDate[dateString]

    fun acknowledge(row: DailyReelData) {
        if (rowsByDate[row.dateString] == row) rowsByDate.remove(row.dateString)
    }

    suspend fun flush(
        write: suspend (DailyReelData) -> Unit,
        onFailure: (DailyReelData, Exception) -> Unit
    ): Boolean {
        for (row in rowsByDate.values.toList()) {
            try {
                write(row)
                acknowledge(row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(row, failure)
            }
        }
        return rowsByDate.isEmpty()
    }
}
