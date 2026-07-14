package com.reeltrak.app.core.di;

import com.reeltrak.app.data.local.dao.SessionDao;
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
public final class DatabaseModule_ProvideSessionDaoFactory implements Factory<SessionDao> {
  private final Provider<ReelTrakDatabase> dbProvider;

  public DatabaseModule_ProvideSessionDaoFactory(Provider<ReelTrakDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public SessionDao get() {
    return provideSessionDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideSessionDaoFactory create(
      Provider<ReelTrakDatabase> dbProvider) {
    return new DatabaseModule_ProvideSessionDaoFactory(dbProvider);
  }

  public static SessionDao provideSessionDao(ReelTrakDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideSessionDao(db));
  }
}
