package com.reeltrak.app.features.dashboard.viewmodel;

import com.reeltrak.app.data.local.dao.DailyReelDataDao;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import com.reeltrak.app.services.notifications.ReelNotificationManager;
import com.reeltrak.app.services.overlay.OverlayStateManager;
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
public final class DashboardViewModel_Factory implements Factory<DashboardViewModel> {
  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  private final Provider<DailyReelDataDao> dailyReelDataDaoProvider;

  private final Provider<OverlayStateManager> overlayStateManagerProvider;

  private final Provider<DayManager> dayManagerProvider;

  private final Provider<ReelNotificationManager> notificationManagerProvider;

  public DashboardViewModel_Factory(Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DailyReelDataDao> dailyReelDataDaoProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider,
      Provider<DayManager> dayManagerProvider,
      Provider<ReelNotificationManager> notificationManagerProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.dailyReelDataDaoProvider = dailyReelDataDaoProvider;
    this.overlayStateManagerProvider = overlayStateManagerProvider;
    this.dayManagerProvider = dayManagerProvider;
    this.notificationManagerProvider = notificationManagerProvider;
  }

  @Override
  public DashboardViewModel get() {
    return newInstance(preferencesRepositoryProvider.get(), dailyReelDataDaoProvider.get(), overlayStateManagerProvider.get(), dayManagerProvider.get(), notificationManagerProvider.get());
  }

  public static DashboardViewModel_Factory create(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DailyReelDataDao> dailyReelDataDaoProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider,
      Provider<DayManager> dayManagerProvider,
      Provider<ReelNotificationManager> notificationManagerProvider) {
    return new DashboardViewModel_Factory(preferencesRepositoryProvider, dailyReelDataDaoProvider, overlayStateManagerProvider, dayManagerProvider, notificationManagerProvider);
  }

  public static DashboardViewModel newInstance(PreferencesRepository preferencesRepository,
      DailyReelDataDao dailyReelDataDao, OverlayStateManager overlayStateManager,
      DayManager dayManager, ReelNotificationManager notificationManager) {
    return new DashboardViewModel(preferencesRepository, dailyReelDataDao, overlayStateManager, dayManager, notificationManager);
  }
}
