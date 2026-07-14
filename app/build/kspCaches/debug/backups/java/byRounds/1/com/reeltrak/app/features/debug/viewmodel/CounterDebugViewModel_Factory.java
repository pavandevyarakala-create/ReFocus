package com.reeltrak.app.features.debug.viewmodel;

import com.reeltrak.app.services.tracking.debug.CounterDebugLogger;
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
public final class CounterDebugViewModel_Factory implements Factory<CounterDebugViewModel> {
  private final Provider<CounterDebugLogger> loggerProvider;

  public CounterDebugViewModel_Factory(Provider<CounterDebugLogger> loggerProvider) {
    this.loggerProvider = loggerProvider;
  }

  @Override
  public CounterDebugViewModel get() {
    return newInstance(loggerProvider.get());
  }

  public static CounterDebugViewModel_Factory create(Provider<CounterDebugLogger> loggerProvider) {
    return new CounterDebugViewModel_Factory(loggerProvider);
  }

  public static CounterDebugViewModel newInstance(CounterDebugLogger logger) {
    return new CounterDebugViewModel(logger);
  }
}
