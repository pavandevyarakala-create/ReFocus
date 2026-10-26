package com.reeltrak.app.services.tracking;

import android.content.Context;
import com.reeltrak.app.core.sync.SyncManager;
import com.reeltrak.app.data.local.dao.DailyReelDataDao;
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager;
import com.reeltrak.app.domain.managers.SubscriptionManager;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import com.reeltrak.app.services.notifications.ReelNotificationManager;
import dagger.Lazy;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class DayManager_Factory implements Factory<DayManager> {
  private final Provider<Context> contextProvider;

  private final Provider<DailyReelDataDao> dailyReelDataDaoProvider;

  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  private final Provider<ReelNotificationManager> notificationManagerProvider;

  private final Provider<SyncManager> syncManagerProvider;

  private final Provider<AppAuthorizationManager> appAuthorizationManagerProvider;

  private final Provider<SubscriptionManager> subscriptionManagerLazyProvider;

  public DayManager_Factory(Provider<Context> contextProvider,
      Provider<DailyReelDataDao> dailyReelDataDaoProvider,
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<ReelNotificationManager> notificationManagerProvider,
      Provider<SyncManager> syncManagerProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerProvider,
      Provider<SubscriptionManager> subscriptionManagerLazyProvider) {
    this.contextProvider = contextProvider;
    this.dailyReelDataDaoProvider = dailyReelDataDaoProvider;
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.notificationManagerProvider = notificationManagerProvider;
    this.syncManagerProvider = syncManagerProvider;
    this.appAuthorizationManagerProvider = appAuthorizationManagerProvider;
    this.subscriptionManagerLazyProvider = subscriptionManagerLazyProvider;
  }

  @Override
  public DayManager get() {
    return newInstance(contextProvider.get(), dailyReelDataDaoProvider.get(), preferencesRepositoryProvider.get(), notificationManagerProvider.get(), syncManagerProvider.get(), appAuthorizationManagerProvider.get(), DoubleCheck.lazy(subscriptionManagerLazyProvider));
  }

  public static DayManager_Factory create(Provider<Context> contextProvider,
      Provider<DailyReelDataDao> dailyReelDataDaoProvider,
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<ReelNotificationManager> notificationManagerProvider,
      Provider<SyncManager> syncManagerProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerProvider,
      Provider<SubscriptionManager> subscriptionManagerLazyProvider) {
    return new DayManager_Factory(contextProvider, dailyReelDataDaoProvider, preferencesRepositoryProvider, notificationManagerProvider, syncManagerProvider, appAuthorizationManagerProvider, subscriptionManagerLazyProvider);
  }

  public static DayManager newInstance(Context context, DailyReelDataDao dailyReelDataDao,
      PreferencesRepository preferencesRepository, ReelNotificationManager notificationManager,
      SyncManager syncManager, AppAuthorizationManager appAuthorizationManager,
      Lazy<SubscriptionManager> subscriptionManagerLazy) {
    return new DayManager(context, dailyReelDataDao, preferencesRepository, notificationManager, syncManager, appAuthorizationManager, subscriptionManagerLazy);
  }
}
