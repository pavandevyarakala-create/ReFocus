package com.reeltrak.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reeltrak.app.data.local.dao.DailyReelDataDao
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

    @Provides
    @Singleton
    fun provideReelTrakDatabase(
        @ApplicationContext context: Context
    ): ReelTrakDatabase {
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

        return Room.databaseBuilder(
            context,
            ReelTrakDatabase::class.java,
            "reeltrak_db"
        )
        .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    @Singleton
    fun provideDailyReelDataDao(db: ReelTrakDatabase): DailyReelDataDao {
        return db.dailyReelDataDao
    }
}
