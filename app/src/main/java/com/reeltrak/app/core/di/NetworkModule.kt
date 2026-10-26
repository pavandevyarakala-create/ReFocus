package com.reeltrak.app.core.di

import com.reeltrak.app.core.network.AppCheckInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Dagger/Hilt Module providing a unified singleton OkHttpClient.
 * 
 * Centralizing OkHttpClient ensures that all repositories, services, and background workers
 * share a single HTTP/2 connection pool, thread pool, socket cache, and AppCheckInterceptor,
 * preventing connection fragmentation, redundant TLS handshakes, and excessive socket allocation (OPT-006).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .addInterceptor(AppCheckInterceptor())
            .build()
    }
}
