package com.reeltrak.app.services.tracking;

import android.content.Context;
import com.reeltrak.app.data.local.dao.DailyStatisticsDao;
import com.reeltrak.app.data.local.dao.GlobalStatisticsDao;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import dagger.internal.DaggerGenerated;
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

  private final Provider<DailyStatisticsDao> dailyStatisticsDaoProvider;

  private final Provider<GlobalStatisticsDao> globalStatisticsDaoProvider;

  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  public DayManager_Factory(Provider<Context> contextProvider,
      Provider<DailyStatisticsDao> dailyStatisticsDaoProvider,
      Provider<GlobalStatisticsDao> globalStatisticsDaoProvider,
      Provider<PreferencesRepository> preferencesRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.dailyStatisticsDaoProvider = dailyStatisticsDaoProvider;
    this.globalStatisticsDaoProvider = globalStatisticsDaoProvider;
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
  }

  @Override
  public DayManager get() {
    return newInstance(contextProvider.get(), dailyStatisticsDaoProvider.get(), globalStatisticsDaoProvider.get(), preferencesRepositoryProvider.get());
  }

  public static DayManager_Factory create(Provider<Context> contextProvider,
      Provider<DailyStatisticsDao> dailyStatisticsDaoProvider,
      Provider<GlobalStatisticsDao> globalStatisticsDaoProvider,
      Provider<PreferencesRepository> preferencesRepositoryProvider) {
    return new DayManager_Factory(contextProvider, dailyStatisticsDaoProvider, globalStatisticsDaoProvider, preferencesRepositoryProvider);
  }

  public static DayManager newInstance(Context context, DailyStatisticsDao dailyStatisticsDao,
      GlobalStatisticsDao globalStatisticsDao, PreferencesRepository preferencesRepository) {
    return new DayManager(context, dailyStatisticsDao, globalStatisticsDao, preferencesRepository);
  }
}
