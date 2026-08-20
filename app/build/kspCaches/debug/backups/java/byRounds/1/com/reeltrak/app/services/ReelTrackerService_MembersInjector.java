package com.reeltrak.app.services;

import com.reeltrak.app.core.sync.SyncManager;
import com.reeltrak.app.domain.repository.PreferencesRepository;
import com.reeltrak.app.services.overlay.OverlayStateManager;
import com.reeltrak.app.services.tracking.DayManager;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class ReelTrackerService_MembersInjector implements MembersInjector<ReelTrackerService> {
  private final Provider<PreferencesRepository> preferencesRepositoryProvider;

  private final Provider<DayManager> dayManagerProvider;

  private final Provider<SyncManager> syncManagerProvider;

  private final Provider<OverlayStateManager> overlayStateManagerProvider;

  public ReelTrackerService_MembersInjector(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider) {
    this.preferencesRepositoryProvider = preferencesRepositoryProvider;
    this.dayManagerProvider = dayManagerProvider;
    this.syncManagerProvider = syncManagerProvider;
    this.overlayStateManagerProvider = overlayStateManagerProvider;
  }

  public static MembersInjector<ReelTrackerService> create(
      Provider<PreferencesRepository> preferencesRepositoryProvider,
      Provider<DayManager> dayManagerProvider, Provider<SyncManager> syncManagerProvider,
      Provider<OverlayStateManager> overlayStateManagerProvider) {
    return new ReelTrackerService_MembersInjector(preferencesRepositoryProvider, dayManagerProvider, syncManagerProvider, overlayStateManagerProvider);
  }

  @Override
  public void injectMembers(ReelTrackerService instance) {
    injectPreferencesRepository(instance, preferencesRepositoryProvider.get());
    injectDayManager(instance, dayManagerProvider.get());
    injectSyncManager(instance, syncManagerProvider.get());
    injectOverlayStateManager(instance, overlayStateManagerProvider.get());
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.preferencesRepository")
  public static void injectPreferencesRepository(ReelTrackerService instance,
      PreferencesRepository preferencesRepository) {
    instance.preferencesRepository = preferencesRepository;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.dayManager")
  public static void injectDayManager(ReelTrackerService instance, DayManager dayManager) {
    instance.dayManager = dayManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.syncManager")
  public static void injectSyncManager(ReelTrackerService instance, SyncManager syncManager) {
    instance.syncManager = syncManager;
  }

  @InjectedFieldSignature("com.reeltrak.app.services.ReelTrackerService.overlayStateManager")
  public static void injectOverlayStateManager(ReelTrackerService instance,
      OverlayStateManager overlayStateManager) {
    instance.overlayStateManager = overlayStateManager;
  }
}
