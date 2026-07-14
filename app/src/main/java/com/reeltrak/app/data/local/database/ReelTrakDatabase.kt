package com.reeltrak.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reeltrak.app.data.local.dao.DailyStatisticsDao
import com.reeltrak.app.data.local.dao.GlobalStatisticsDao
import com.reeltrak.app.data.local.dao.SessionDao
import com.reeltrak.app.data.local.entity.DailyStatisticsEntity
import com.reeltrak.app.data.local.entity.GlobalStatisticsEntity
import com.reeltrak.app.data.local.entity.SessionEntity

@Database(entities = [SessionEntity::class, DailyStatisticsEntity::class, GlobalStatisticsEntity::class], version = 5, exportSchema = false)
abstract class ReelTrakDatabase : RoomDatabase() {
    abstract val sessionDao: SessionDao
    abstract val dailyStatisticsDao: DailyStatisticsDao
    abstract val globalStatisticsDao: GlobalStatisticsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_statistics` (" +
                            "`dateEpochDay` INTEGER NOT NULL, " +
                            "`totalReelsScrolled` INTEGER NOT NULL, " +
                            "`lastUpdated` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`dateEpochDay`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_statistics ADD COLUMN dailyGoal INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_statistics ADD COLUMN completionPercentage REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE daily_statistics ADD COLUMN colorLevel INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `global_statistics` (" +
                            "`id` INTEGER NOT NULL, " +
                            "`lifetimeReels` INTEGER NOT NULL, " +
                            "`completedDays` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
