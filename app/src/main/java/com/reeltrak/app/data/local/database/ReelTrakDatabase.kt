package com.reeltrak.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.dao.ActiveAccountScopeDao
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao
import com.reeltrak.app.data.local.entity.AcceptedReelTransitionState
import com.reeltrak.app.data.local.entity.ActiveAccountScope
import com.reeltrak.app.data.local.entity.Converters
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.LocalRestoreJournal

@Database(
    entities = [DailyReelData::class, LocalRestoreJournal::class, AcceptedReelTransitionState::class, ActiveAccountScope::class],
    version = 14,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ReelTrakDatabase : RoomDatabase() {
    abstract val dailyReelDataDao: DailyReelDataDao
    abstract val localRestoreJournalDao: LocalRestoreJournalDao
    abstract val activeAccountScopeDao: ActiveAccountScopeDao
}
