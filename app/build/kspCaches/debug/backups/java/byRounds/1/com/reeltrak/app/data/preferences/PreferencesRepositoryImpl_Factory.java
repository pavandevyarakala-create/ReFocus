package com.reeltrak.app.data.preferences;

import android.content.Context;
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
public final class PreferencesRepositoryImpl_Factory implements Factory<PreferencesRepositoryImpl> {
  private final Provider<Context> contextProvider;

  public PreferencesRepositoryImpl_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public PreferencesRepositoryImpl get() {
    return newInstance(contextProvider.get());
  }

  public static PreferencesRepositoryImpl_Factory create(Provider<Context> contextProvider) {
    return new PreferencesRepositoryImpl_Factory(contextProvider);
  }

  public static PreferencesRepositoryImpl newInstance(Context context) {
    return new PreferencesRepositoryImpl(context);
  }
}
