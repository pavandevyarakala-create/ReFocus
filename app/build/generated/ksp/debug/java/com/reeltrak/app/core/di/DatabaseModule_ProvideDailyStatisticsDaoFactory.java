package com.reeltrak.app.core.di;

import com.reeltrak.app.data.local.dao.DailyStatisticsDao;
import com.reeltrak.app.data.local.database.ReelTrakDatabase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
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
public final class DatabaseModule_ProvideDailyStatisticsDaoFactory implements Factory<DailyStatisticsDao> {
  private final Provider<ReelTrakDatabase> dbProvider;

  public DatabaseModule_ProvideDailyStatisticsDaoFactory(Provider<ReelTrakDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public DailyStatisticsDao get() {
    return provideDailyStatisticsDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideDailyStatisticsDaoFactory create(
      Provider<ReelTrakDatabase> dbProvider) {
    return new DatabaseModule_ProvideDailyStatisticsDaoFactory(dbProvider);
  }

  public static DailyStatisticsDao provideDailyStatisticsDao(ReelTrakDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideDailyStatisticsDao(db));
  }
}
