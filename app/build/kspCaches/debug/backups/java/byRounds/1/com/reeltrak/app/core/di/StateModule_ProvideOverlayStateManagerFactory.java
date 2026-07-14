package com.reeltrak.app.core.di;

import com.reeltrak.app.services.overlay.OverlayStateManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class StateModule_ProvideOverlayStateManagerFactory implements Factory<OverlayStateManager> {
  @Override
  public OverlayStateManager get() {
    return provideOverlayStateManager();
  }

  public static StateModule_ProvideOverlayStateManagerFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static OverlayStateManager provideOverlayStateManager() {
    return Preconditions.checkNotNullFromProvides(StateModule.INSTANCE.provideOverlayStateManager());
  }

  private static final class InstanceHolder {
    private static final StateModule_ProvideOverlayStateManagerFactory INSTANCE = new StateModule_ProvideOverlayStateManagerFactory();
  }
}
