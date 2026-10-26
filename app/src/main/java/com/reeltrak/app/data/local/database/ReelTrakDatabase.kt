package com.reeltrak.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao
import com.reeltrak.app.data.local.entity.AcceptedReelTransitionState
import com.reeltrak.app.data.local.entity.Converters
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.LocalRestoreJournal

@Database(
    entities = [DailyReelData::class, LocalRestoreJournal::class, AcceptedReelTransitionState::class],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ReelTrakDatabase : RoomDatabase() {
    abstract val dailyReelDataDao: DailyReelDataDao
    abstract val localRestoreJournalDao: LocalRestoreJournalDao
}
