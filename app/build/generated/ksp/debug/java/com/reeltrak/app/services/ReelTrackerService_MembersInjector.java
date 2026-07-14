package com.reeltrak.app.services;

import com.reeltrak.app.data.local.dao.DailyStatisticsDao;
import com.reeltrak.app.data.local.dao.SessionDao;
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
  private final Provider<SessionDao> sessionDaoProvider;

  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  private final Provider<DailyStatisticsDao> dailyStatisticsDaoProvider;

  private final Provider<DayManager> dayManagerProvider;

  private final Provider<OverlayStateManager> overlayStateManagerProvider;

  public ReelTrackerService_MembersInjector(Provider<SessionDao> sessionDaoProvider,
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DailyStatisticsDao> dailyStatisticsDaoProvider,
      Provider<DayManager> dayManagerProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider) {
    this.sessionDaoProvider = sessionDaoProvider;
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.dailyStatisticsDaoProvider = dailyStatisticsDaoProvider;
    this.dayManagerProvider = dayManagerProvider;
    this.overlayStateManagerProvider = overlayStateManagerProvider;
  }

  public static MembersInjector<ReelTrackerService> create(Provider<SessionDao> sessionDaoProvider,
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DailyStatisticsDao> dailyStatisticsDaoProvider,
      Provider<DayManager> dayManagerProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider) {
    return new ReelTrackerService_MembersInjector(sessionDaoProvider, preferencesRepositoryProvider, dailyStatisticsDaoProvider, dayManagerProvider, overlayStateManagerProvider);
  }

  @Override
  public void injectMembers(ReelTrackerService instance) {
    injectSessionDao(instance, sessionDaoProvider.get());
    injectPreferencesRepository(instance, preferencesRepositoryProvider.get());
    injectDailyStatisticsDao(instance, dailyStatisticsDaoProvider.get());
    injectDayManager(instance, dayManagerProvider.get());
    injectOverlayStateManager(instance, overlayStateManagerProvider.get());
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.sessionDao")
  public static void injectSessionDao(ReelTrackerService instance, SessionDao sessionDao) {
    instance.sessionDao = sessionDao;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.preferencesRepository")
  public static void injectPreferencesRepository(ReelTrackerService instance,
      PreferencesRepository preferencesRepository) {
    instance.preferencesRepository = preferencesRepository;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.dailyStatisticsDao")
  public static void injectDailyStatisticsDao(ReelTrackerService instance,
      DailyStatisticsDao dailyStatisticsDao) {
    instance.dailyStatisticsDao = dailyStatisticsDao;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.dayManager")
  public static void injectDayManager(ReelTrackerService instance, DayManager dayManager) {
    instance.dayManager = dayManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.overlayStateManager")
  public static void injectOverlayStateManager(ReelTrackerService instance,
      OverlayStateManager overlayStateManager) {
    instance.overlayStateManager = overlayStateManager;
  }
}
