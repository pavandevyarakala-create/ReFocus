package com.reeltrak.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.dao.ActiveAccountScopeDao
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao
import com.reeltrak.app.data.local.database.ReelTrakDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `daily_statistics` (
                    `dateEpochDay` INTEGER NOT NULL,
                    `totalReelsScrolled` INTEGER NOT NULL,
                    `lastUpdated` INTEGER NOT NULL,
                    PRIMARY KEY(`dateEpochDay`)
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE daily_statistics ADD COLUMN dailyGoal INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE daily_statistics ADD COLUMN completionPercentage REAL NOT NULL DEFAULT 0.0")
            database.execSQL("ALTER TABLE daily_statistics ADD COLUMN colorLevel INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `global_statistics` (
                    `id` INTEGER NOT NULL,
                    `lifetimeReels` INTEGER NOT NULL,
                    `completedDays` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_5_8 = object : Migration(5, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily_reel_data (
                    dateString TEXT NOT NULL PRIMARY KEY,
                    totalReelsCount INTEGER NOT NULL,
                    totalTimeSpentMillis INTEGER NOT NULL DEFAULT 0,
                    dailyGoal INTEGER NOT NULL DEFAULT 0,
                    isSkullDay INTEGER NOT NULL DEFAULT 0,
                    isStrictModeOn INTEGER NOT NULL DEFAULT 0,
                    sessions TEXT NOT NULL DEFAULT '[]',
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT OR IGNORE INTO daily_reel_data
                    (dateString, totalReelsCount, totalTimeSpentMillis, dailyGoal, isSkullDay, isStrictModeOn, sessions, updatedAt)
                SELECT 
                    CASE 
                        WHEN dateEpochDay > 1000000000000 THEN date(dateEpochDay / 1000, 'unixepoch')
                        WHEN dateEpochDay > 1000000000 THEN date(dateEpochDay, 'unixepoch')
                        ELSE date(dateEpochDay * 86400, 'unixepoch')
                    END,
                    totalReelsScrolled,
                    0,
                    dailyGoal,
                    0,
                    0,
                    '[]',
                    lastUpdated
                FROM daily_statistics
                """.trimIndent()
            )
            database.execSQL("DROP TABLE IF EXISTS daily_statistics")
            database.execSQL("DROP TABLE IF EXISTS scroll_sessions")
            database.execSQL("DROP TABLE IF EXISTS global_statistics")
        }
    }

    val MIGRATION_6_8 = object : Migration(6, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            MIGRATION_5_8.migrate(database)
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            MIGRATION_5_8.migrate(database)
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE daily_reel_data ADD COLUMN isLimitLocked INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE daily_reel_data ADD COLUMN isStrictModeLocked INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE daily_reel_data ADD COLUMN syncStatus INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_restore_journal (
                    idempotency_key TEXT PRIMARY KEY NOT NULL,
                    backup_id TEXT NOT NULL,
                    session_date_ist TEXT NOT NULL,
                    applied_at_ms INTEGER NOT NULL,
                    records_restored INTEGER NOT NULL,
                    restored_daily_limit INTEGER,
                    ack_status TEXT NOT NULL,
                    datastore_synced INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS accepted_reel_transition_state (
                    account_scope TEXT NOT NULL,
                    date_string TEXT NOT NULL,
                    platform TEXT NOT NULL,
                    primary_identifier TEXT NOT NULL,
                    secondary_identifier TEXT NOT NULL,
                    auxiliary_identifier TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(account_scope, date_string)
                )
                """.trimIndent()
            )
        }
    }

    // Preserve pre-v14 records by migrating them under the 'local' account scope.
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE daily_reel_data_new (
                    account_scope TEXT NOT NULL,
                    dateString TEXT NOT NULL,
                    totalReelsCount INTEGER NOT NULL,
                    totalTimeSpentMillis INTEGER NOT NULL,
                    dailyGoal INTEGER NOT NULL,
                    isSkullDay INTEGER NOT NULL,
                    isStrictModeOn INTEGER NOT NULL,
                    isStrictModeLocked INTEGER NOT NULL,
                    isLimitLocked INTEGER NOT NULL,
                    sessions TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    syncStatus INTEGER NOT NULL,
                    PRIMARY KEY(account_scope, dateString)
                )
                """.trimIndent()
            )
            database.execSQL(
                """INSERT INTO daily_reel_data_new
                    (account_scope, dateString, totalReelsCount, totalTimeSpentMillis, dailyGoal,
                     isSkullDay, isStrictModeOn, isStrictModeLocked, isLimitLocked, sessions, updatedAt, syncStatus)
                    SELECT 'local', dateString, totalReelsCount, totalTimeSpentMillis, dailyGoal,
                           isSkullDay, isStrictModeOn, isStrictModeLocked, isLimitLocked, sessions, updatedAt, syncStatus
                    FROM daily_reel_data""".trimIndent()
            )
            database.execSQL("DROP TABLE daily_reel_data")
            database.execSQL("ALTER TABLE daily_reel_data_new RENAME TO daily_reel_data")

            database.execSQL(
                """
                CREATE TABLE local_restore_journal_new (
                    account_scope TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    backup_id TEXT NOT NULL,
                    session_date_ist TEXT NOT NULL,
                    applied_at_ms INTEGER NOT NULL,
                    records_restored INTEGER NOT NULL,
                    restored_daily_limit INTEGER,
                    ack_status TEXT NOT NULL,
                    datastore_synced INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(account_scope, idempotency_key)
                )
                """.trimIndent()
            )
            database.execSQL(
                """INSERT INTO local_restore_journal_new
                    (account_scope, idempotency_key, backup_id, session_date_ist, applied_at_ms,
                     records_restored, restored_daily_limit, ack_status, datastore_synced)
                    SELECT 'local', idempotency_key, backup_id, session_date_ist, applied_at_ms,
                           records_restored, restored_daily_limit, ack_status, datastore_synced
                    FROM local_restore_journal""".trimIndent()
            )
            database.execSQL("DROP TABLE local_restore_journal")
            database.execSQL("ALTER TABLE local_restore_journal_new RENAME TO local_restore_journal")

            database.execSQL(
                """
                CREATE TABLE active_account_scope (
                    singleton_id INTEGER NOT NULL PRIMARY KEY,
                    account_scope TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL("INSERT INTO active_account_scope (singleton_id, account_scope) VALUES (1, 'local')")
        }
    }

    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_8,
        MIGRATION_6_8,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14
    )

    @Provides
    @Singleton
    fun provideReelTrakDatabase(
        @ApplicationContext context: Context
    ): ReelTrakDatabase {
        return Room.databaseBuilder(
            context,
            ReelTrakDatabase::class.java,
            "reeltrak_db"
        )
        .addMigrations(*ALL_MIGRATIONS)
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                try {
                    db.execSQL("UPDATE daily_reel_data SET account_scope = 'local' WHERE account_scope = '__legacy_unassigned__'")
                    db.execSQL("UPDATE local_restore_journal SET account_scope = 'local' WHERE account_scope = '__legacy_unassigned__'")
                } catch (_: Exception) {}
            }
        })
        .build()
    }

    @Provides
    @Singleton
    fun provideDailyReelDataDao(db: ReelTrakDatabase): DailyReelDataDao {
        return db.dailyReelDataDao
    }

    @Provides
    @Singleton
    fun provideLocalRestoreJournalDao(db: ReelTrakDatabase): LocalRestoreJournalDao {
        return db.localRestoreJournalDao
    }

    @Provides
    @Singleton
    fun provideActiveAccountScopeDao(db: ReelTrakDatabase): ActiveAccountScopeDao = db.activeAccountScopeDao
}
