package com.reeltrak.app.features.dashboard.viewmodel;

import com.reeltrak.app.data.local.dao.DailyStatisticsDao;
import com.reeltrak.app.data.local.dao.GlobalStatisticsDao;
import com.reeltrak.app.data.local.dao.SessionDao;
import com.reeltrak.app.domain.repository.PreferencesRepository;
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

  private final Provider<SessionDao> sessionDaoProvider;

  private final Provider<DailyStatisticsDao> dailyStatisticsDaoProvider;

  private final Provider<GlobalStatisticsDao> globalStatisticsDaoProvider;

  private final Provider<OverlayStateManager> overlayStateManagerProvider;

  private final Provider<DayManager> dayManagerProvider;

  public DashboardViewModel_Factory(Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<SessionDao> sessionDaoProvider,
      Provider<DailyStatisticsDao> dailyStatisticsDaoProvider,
      Provider<GlobalStatisticsDao> globalStatisticsDaoProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider,
      Provider<DayManager> dayManagerProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.sessionDaoProvider = sessionDaoProvider;
    this.dailyStatisticsDaoProvider = dailyStatisticsDaoProvider;
    this.globalStatisticsDaoProvider = globalStatisticsDaoProvider;
    this.overlayStateManagerProvider = overlayStateManagerProvider;
    this.dayManagerProvider = dayManagerProvider;
  }

  @Override
  public DashboardViewModel get() {
    return newInstance(preferencesRepositoryProvider.get(), sessionDaoProvider.get(), dailyStatisticsDaoProvider.get(), globalStatisticsDaoProvider.get(), overlayStateManagerProvider.get(), dayManagerProvider.get());
  }

  public static DashboardViewModel_Factory create(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<SessionDao> sessionDaoProvider,
      Provider<DailyStatisticsDao> dailyStatisticsDaoProvider,
      Provider<GlobalStatisticsDao> globalStatisticsDaoProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider,
      Provider<DayManager> dayManagerProvider) {
    return new DashboardViewModel_Factory(preferencesRepositoryProvider, sessionDaoProvider, dailyStatisticsDaoProvider, globalStatisticsDaoProvider, overlayStateManagerProvider, dayManagerProvider);
  }

  public static DashboardViewModel newInstance(PreferencesRepository preferencesRepository,
      SessionDao sessionDao, DailyStatisticsDao dailyStatisticsDao,
      GlobalStatisticsDao globalStatisticsDao, OverlayStateManager overlayStateManager,
      DayManager dayManager) {
    return new DashboardViewModel(preferencesRepository, sessionDao, dailyStatisticsDao, globalStatisticsDao, overlayStateManager, dayManager);
  }
}
