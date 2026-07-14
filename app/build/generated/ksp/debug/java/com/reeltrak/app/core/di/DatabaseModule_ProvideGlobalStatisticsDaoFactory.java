package com.reeltrak.app.core.di;

import com.reeltrak.app.data.local.dao.GlobalStatisticsDao;
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
public final class DatabaseModule_ProvideGlobalStatisticsDaoFactory implements Factory<GlobalStatisticsDao> {
  private final Provider<ReelTrakDatabase> dbProvider;

  public DatabaseModule_ProvideGlobalStatisticsDaoFactory(Provider<ReelTrakDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public GlobalStatisticsDao get() {
    return provideGlobalStatisticsDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideGlobalStatisticsDaoFactory create(
      Provider<ReelTrakDatabase> dbProvider) {
    return new DatabaseModule_ProvideGlobalStatisticsDaoFactory(dbProvider);
  }

  public static GlobalStatisticsDao provideGlobalStatisticsDao(ReelTrakDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideGlobalStatisticsDao(db));
  }
}
