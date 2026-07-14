package com.reeltrak.app.core.di

import com.reeltrak.app.services.overlay.OverlayStateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StateModule {
    
    @Provides
    @Singleton
    fun provideOverlayStateManager(): OverlayStateManager {
        return OverlayStateManager()
    }
}
