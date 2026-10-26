package com.reeltrak.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reeltrak.app.data.local.dao.DailyReelDataDao
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
        .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
        .fallbackToDestructiveMigration()
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
}
