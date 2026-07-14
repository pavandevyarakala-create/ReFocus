package com.reeltrak.app.features.settings.viewmodel;

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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  public SettingsViewModel_Factory(Provider<PreferencesRepository> preferencesRepositoryProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(preferencesRepositoryProvider.get());
  }

  public static SettingsViewModel_Factory create(
      Provider<PreferencesRepository> preferencesRepositoryProvider) {
    return new SettingsViewModel_Factory(preferencesRepositoryProvider);
  }

  public static SettingsViewModel newInstance(PreferencesRepository preferencesRepository) {
    return new SettingsViewModel(preferencesRepository);
  }
}
