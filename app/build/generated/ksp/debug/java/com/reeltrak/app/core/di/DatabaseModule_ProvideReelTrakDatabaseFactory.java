package com.reeltrak.app.core.di;

import android.content.Context;
import com.reeltrak.app.data.local.database.ReelTrakDatabase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class DatabaseModule_ProvideReelTrakDatabaseFactory implements Factory<ReelTrakDatabase> {
  private final Provider<Context> contextProvider;

  public DatabaseModule_ProvideReelTrakDatabaseFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public ReelTrakDatabase get() {
    return provideReelTrakDatabase(contextProvider.get());
  }

  public static DatabaseModule_ProvideReelTrakDatabaseFactory create(
      Provider<Context> contextProvider) {
    return new DatabaseModule_ProvideReelTrakDatabaseFactory(contextProvider);
  }

  public static ReelTrakDatabase provideReelTrakDatabase(Context context) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideReelTrakDatabase(context));
  }
}
