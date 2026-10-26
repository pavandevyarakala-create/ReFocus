package com.reeltrak.app.features.settings.viewmodel;

import com.reeltrak.app.core.sync.SyncManager;
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager;
import com.reeltrak.app.domain.managers.SubscriptionManager;
import com.reeltrak.app.domain.repository.AccountRepository;
import com.reeltrak.app.domain.repository.OnboardingRepository;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import com.reeltrak.app.domain.services.SubscriptionPurchaseService;
import com.reeltrak.app.services.tracking.DayManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast"
})
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  private final Provider<AccountRepository> accountRepositoryProvider;

  private final Provider<OnboardingRepository> onboardingRepositoryProvider;

  private final Provider<SubscriptionManager> subscriptionManagerProvider;

  private final Provider<SubscriptionPurchaseService> purchaseServiceProvider;

  private final Provider<DayManager> dayManagerProvider;

  private final Provider<SyncManager> syncManagerProvider;

  private final Provider<AppAuthorizationManager> appAuthorizationManagerProvider;

  public SettingsViewModel_Factory(Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<AccountRepository> accountRepositoryProvider,
      Provider<OnboardingRepository> onboardingRepositoryProvider,
      Provider<SubscriptionManager> subscriptionManagerProvider,
      Provider<SubscriptionPurchaseService> purchaseServiceProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.accountRepositoryProvider = accountRepositoryProvider;
    this.onboardingRepositoryProvider = onboardingRepositoryProvider;
    this.subscriptionManagerProvider = subscriptionManagerProvider;
    this.purchaseServiceProvider = purchaseServiceProvider;
    this.dayManagerProvider = dayManagerProvider;
    this.syncManagerProvider = syncManagerProvider;
    this.appAuthorizationManagerProvider = appAuthorizationManagerProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(preferencesRepositoryProvider.get(), accountRepositoryProvider.get(), onboardingRepositoryProvider.get(), subscriptionManagerProvider.get(), purchaseServiceProvider.get(), dayManagerProvider.get(), syncManagerProvider.get(), appAuthorizationManagerProvider.get());
  }

  public static SettingsViewModel_Factory create(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<AccountRepository> accountRepositoryProvider,
      Provider<OnboardingRepository> onboardingRepositoryProvider,
      Provider<SubscriptionManager> subscriptionManagerProvider,
      Provider<SubscriptionPurchaseService> purchaseServiceProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerProvider) {
    return new SettingsViewModel_Factory(preferencesRepositoryProvider, accountRepositoryProvider, onboardingRepositoryProvider, subscriptionManagerProvider, purchaseServiceProvider, dayManagerProvider, syncManagerProvider, appAuthorizationManagerProvider);
  }

  public static SettingsViewModel newInstance(PreferencesRepository preferencesRepository,
      AccountRepository accountRepository, OnboardingRepository onboardingRepository,
      SubscriptionManager subscriptionManager, SubscriptionPurchaseService purchaseService,
      DayManager dayManager, SyncManager syncManager,
      AppAuthorizationManager appAuthorizationManager) {
    return new SettingsViewModel(preferencesRepository, accountRepository, onboardingRepository, subscriptionManager, purchaseService, dayManager, syncManager, appAuthorizationManager);
  }
}
