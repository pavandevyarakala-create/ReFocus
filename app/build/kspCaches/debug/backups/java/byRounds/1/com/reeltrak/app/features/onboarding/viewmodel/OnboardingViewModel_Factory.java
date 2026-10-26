package com.reeltrak.app.features.onboarding.viewmodel;

import com.reeltrak.app.data.security.authorization.AppAuthorizationManager;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import dagger.Lazy;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
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
public final class OnboardingViewModel_Factory implements Factory<OnboardingViewModel> {
  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  private final Provider<AppAuthorizationManager> appAuthorizationManagerLazyProvider;

  public OnboardingViewModel_Factory(Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerLazyProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.appAuthorizationManagerLazyProvider = appAuthorizationManagerLazyProvider;
  }

  @Override
  public OnboardingViewModel get() {
    return newInstance(preferencesRepositoryProvider.get(), DoubleCheck.lazy(appAuthorizationManagerLazyProvider));
  }

  public static OnboardingViewModel_Factory create(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<AppAuthorizationManager> appAuthorizationManagerLazyProvider) {
    return new OnboardingViewModel_Factory(preferencesRepositoryProvider, appAuthorizationManagerLazyProvider);
  }

  public static OnboardingViewModel newInstance(PreferencesRepository preferencesRepository,
      Lazy<AppAuthorizationManager> appAuthorizationManagerLazy) {
    return new OnboardingViewModel(preferencesRepository, appAuthorizationManagerLazy);
  }
}
