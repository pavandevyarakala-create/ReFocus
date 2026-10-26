package com.reeltrak.app.data.repository;

import android.content.Context;
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager;
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
public final class PreferencesRepositoryImpl_Factory implements Factory<PreferencesRepositoryImpl> {
  private final Provider<Context> contextProvider;

  private final Provider<AppAuthorizationManager> appAuthorizationManagerLazyProvider;

  public PreferencesRepositoryImpl_Factory(Provider<Context> contextProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerLazyProvider) {
    this.contextProvider = contextProvider;
    this.appAuthorizationManagerLazyProvider = appAuthorizationManagerLazyProvider;
  }

  @Override
  public PreferencesRepositoryImpl get() {
    return newInstance(contextProvider.get(), DoubleCheck.lazy(appAuthorizationManagerLazyProvider));
  }

  public static PreferencesRepositoryImpl_Factory create(Provider<Context> contextProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerLazyProvider) {
    return new PreferencesRepositoryImpl_Factory(contextProvider, appAuthorizationManagerLazyProvider);
  }

  public static PreferencesRepositoryImpl newInstance(Context context,
      Lazy<AppAuthorizationManager> appAuthorizationManagerLazy) {
    return new PreferencesRepositoryImpl(context, appAuthorizationManagerLazy);
  }
}
