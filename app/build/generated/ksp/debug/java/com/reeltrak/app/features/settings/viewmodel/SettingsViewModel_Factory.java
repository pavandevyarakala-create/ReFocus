package com.reeltrak.app.features.settings.viewmodel;

import com.reeltrak.app.core.sync.SyncManager;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import com.reeltrak.app.services.tracking.DayManager;
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

  private final Provider<DayManager> dayManagerProvider;

  private final Provider<SyncManager> syncManagerProvider;

  public SettingsViewModel_Factory(Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.dayManagerProvider = dayManagerProvider;
    this.syncManagerProvider = syncManagerProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(preferencesRepositoryProvider.get(), dayManagerProvider.get(), syncManagerProvider.get());
  }

  public static SettingsViewModel_Factory create(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider) {
    return new SettingsViewModel_Factory(preferencesRepositoryProvider, dayManagerProvider, syncManagerProvider);
  }

  public static SettingsViewModel newInstance(PreferencesRepository preferencesRepository,
      DayManager dayManager, SyncManager syncManager) {
    return new SettingsViewModel(preferencesRepository, dayManager, syncManager);
  }
}
