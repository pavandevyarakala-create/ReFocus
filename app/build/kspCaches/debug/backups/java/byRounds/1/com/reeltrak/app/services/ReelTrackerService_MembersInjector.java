package com.reeltrak.app.services;

import com.reeltrak.app.core.sync.SyncManager;
import com.reeltrak.app.core.update.InAppUpdateManager;
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager;
import com.reeltrak.app.domain.managers.SubscriptionManager;
import com.reeltrak.app.domain.repository.AccountRepository;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import com.reeltrak.app.services.overlay.OverlayStateManager;
import com.reeltrak.app.services.tracking.DayManager;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class ReelTrackerService_MembersInjector implements MembersInjector<ReelTrackerService> {
  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  private final Provider<DayManager> dayManagerProvider;

  private final Provider<SyncManager> syncManagerProvider;

  private final Provider<SubscriptionManager> subscriptionManagerProvider;

  private final Provider<AccountRepository> accountRepositoryProvider;

  private final Provider<AppAuthorizationManager> appAuthorizationManagerProvider;

  private final Provider<InAppUpdateManager> inAppUpdateManagerProvider;

  private final Provider<OverlayStateManager> overlayStateManagerProvider;

  public ReelTrackerService_MembersInjector(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider,
      Provider<SubscriptionManager> subscriptionManagerProvider,
      Provider<AccountRepository> accountRepositoryProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerProvider,
      Provider<InAppUpdateManager> inAppUpdateManagerProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.dayManagerProvider = dayManagerProvider;
    this.syncManagerProvider = syncManagerProvider;
    this.subscriptionManagerProvider = subscriptionManagerProvider;
    this.accountRepositoryProvider = accountRepositoryProvider;
    this.appAuthorizationManagerProvider = appAuthorizationManagerProvider;
    this.inAppUpdateManagerProvider = inAppUpdateManagerProvider;
    this.overlayStateManagerProvider = overlayStateManagerProvider;
  }

  public static MembersInjector<ReelTrackerService> create(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider,
      Provider<SubscriptionManager> subscriptionManagerProvider,
      Provider<AccountRepository> accountRepositoryProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerProvider,
      Provider<InAppUpdateManager> inAppUpdateManagerProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider) {
    return new ReelTrackerService_MembersInjector(preferencesRepositoryProvider, dayManagerProvider, syncManagerProvider, subscriptionManagerProvider, accountRepositoryProvider, appAuthorizationManagerProvider, inAppUpdateManagerProvider, overlayStateManagerProvider);
  }

  @Override
  public void injectMembers(ReelTrackerService instance) {
    injectPreferencesRepository(instance, preferencesRepositoryProvider.get());
    injectDayManager(instance, dayManagerProvider.get());
    injectSyncManager(instance, syncManagerProvider.get());
    injectSubscriptionManager(instance, subscriptionManagerProvider.get());
    injectAccountRepository(instance, accountRepositoryProvider.get());
    injectAppAuthorizationManager(instance, appAuthorizationManagerProvider.get());
    injectInAppUpdateManager(instance, inAppUpdateManagerProvider.get());
    injectOverlayStateManager(instance, overlayStateManagerProvider.get());
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.preferencesRepository")
  public static void injectPreferencesRepository(ReelTrackerService instance,
      PreferencesRepository preferencesRepository) {
    instance.preferencesRepository = preferencesRepository;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.dayManager")
  public static void injectDayManager(ReelTrackerService instance, DayManager dayManager) {
    instance.dayManager = dayManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.syncManager")
  public static void injectSyncManager(ReelTrackerService instance, SyncManager syncManager) {
    instance.syncManager = syncManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.subscriptionManager")
  public static void injectSubscriptionManager(ReelTrackerService instance,
      SubscriptionManager subscriptionManager) {
    instance.subscriptionManager = subscriptionManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.accountRepository")
  public static void injectAccountRepository(ReelTrackerService instance,
      AccountRepository accountRepository) {
    instance.accountRepository = accountRepository;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.appAuthorizationManager")
  public static void injectAppAuthorizationManager(ReelTrackerService instance,
      AppAuthorizationManager appAuthorizationManager) {
    instance.appAuthorizationManager = appAuthorizationManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.inAppUpdateManager")
  public static void injectInAppUpdateManager(ReelTrackerService instance,
      InAppUpdateManager inAppUpdateManager) {
    instance.inAppUpdateManager = inAppUpdateManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.overlayStateManager")
  public static void injectOverlayStateManager(ReelTrackerService instance,
      OverlayStateManager overlayStateManager) {
    instance.overlayStateManager = overlayStateManager;
  }
}
