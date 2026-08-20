package com.reeltrak.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.Converters
import com.reeltrak.app.data.local.entity.DailyReelData

@Database(
    entities = [DailyReelData::class],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ReelTrakDatabase : RoomDatabase() {
    abstract val dailyReelDataDao: DailyReelDataDao
}
