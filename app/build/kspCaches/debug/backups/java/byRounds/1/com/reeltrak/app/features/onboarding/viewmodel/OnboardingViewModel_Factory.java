package com.reeltrak.app.features.onboarding.viewmodel;

import com.reeltrak.app.domain.repository.PreferencesRepository;
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
public final class OnboardingViewModel_Factory implements Factory<OnboardingViewModel> {
  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  public OnboardingViewModel_Factory(
      Provider<PreferencesRepository> preferencesRepositoryProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
  }

  @Override
  public OnboardingViewModel get() {
    return newInstance(preferencesRepositoryProvider.get());
  }

  public static OnboardingViewModel_Factory create(
      Provider<PreferencesRepository> preferencesRepositoryProvider) {
    return new OnboardingViewModel_Factory(preferencesRepositoryProvider);
  }

  public static OnboardingViewModel newInstance(PreferencesRepository preferencesRepository) {
    return new OnboardingViewModel(preferencesRepository);
  }
}
