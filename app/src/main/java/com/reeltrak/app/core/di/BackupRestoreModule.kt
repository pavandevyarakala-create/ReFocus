package com.reeltrak.app.core.di

import com.reeltrak.app.data.repository.LocalBackupRepositoryImpl
import com.reeltrak.app.data.repository.LocalRestoreRepositoryImpl
import com.reeltrak.app.domain.repository.LocalBackupRepository
import com.reeltrak.app.domain.repository.LocalRestoreRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupRestoreModule {

    @Binds
    @Singleton
    abstract fun bindLocalBackupRepository(
        impl: LocalBackupRepositoryImpl
    ): LocalBackupRepository

    @Binds
    @Singleton
    abstract fun bindLocalRestoreRepository(
        impl: LocalRestoreRepositoryImpl
    ): LocalRestoreRepository
}
