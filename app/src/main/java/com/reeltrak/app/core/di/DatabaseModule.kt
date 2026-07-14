package com.reeltrak.app.core.di

import android.content.Context
import androidx.room.Room
import com.reeltrak.app.data.local.dao.SessionDao
import com.reeltrak.app.data.local.database.ReelTrakDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.reeltrak.app.data.local.dao.DailyStatisticsDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

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
        .addMigrations(
            ReelTrakDatabase.MIGRATION_1_2, 
            ReelTrakDatabase.MIGRATION_2_3, 
            ReelTrakDatabase.MIGRATION_3_4,
            ReelTrakDatabase.MIGRATION_4_5
        )
        .build()
    }

    @Provides
    @Singleton
    fun provideSessionDao(db: ReelTrakDatabase): SessionDao {
        return db.sessionDao
    }

    @Provides
    @Singleton
    fun provideDailyStatisticsDao(db: ReelTrakDatabase): DailyStatisticsDao {
        return db.dailyStatisticsDao
    }

    @Provides
    @Singleton
    fun provideGlobalStatisticsDao(db: ReelTrakDatabase): com.reeltrak.app.data.local.dao.GlobalStatisticsDao {
        return db.globalStatisticsDao
    }
}
