package com.reeltrak.app.core.di

import com.reeltrak.app.data.repository.AccountRepositoryImpl
import com.reeltrak.app.data.repository.OnboardingRepositoryImpl
import com.reeltrak.app.data.repository.PreferencesRepositoryImpl
import com.reeltrak.app.data.repository.SubscriptionRepositoryImpl
import com.reeltrak.app.data.services.RazorpaySubscriptionPurchaseServiceImpl
import com.reeltrak.app.domain.repository.AccountRepository
import com.reeltrak.app.domain.repository.OnboardingRepository
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.domain.repository.SubscriptionRepository
import com.reeltrak.app.domain.services.SubscriptionPurchaseService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    @Singleton
    abstract fun bindAccountRepository(
        accountRepositoryImpl: AccountRepositoryImpl
    ): AccountRepository

    @Binds
    @Singleton
    abstract fun bindOnboardingRepository(
        onboardingRepositoryImpl: OnboardingRepositoryImpl
    ): OnboardingRepository

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(
        preferencesRepositoryImpl: PreferencesRepositoryImpl
    ): PreferencesRepository

    @Binds
    @Singleton
    abstract fun bindSubscriptionRepository(
        subscriptionRepositoryImpl: SubscriptionRepositoryImpl
    ): SubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindSubscriptionPurchaseService(
        purchaseServiceImpl: RazorpaySubscriptionPurchaseServiceImpl
    ): SubscriptionPurchaseService
}

