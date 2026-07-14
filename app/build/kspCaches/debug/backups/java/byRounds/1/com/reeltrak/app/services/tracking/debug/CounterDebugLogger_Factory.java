package com.reeltrak.app.services.tracking.debug;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class CounterDebugLogger_Factory implements Factory<CounterDebugLogger> {
  @Override
  public CounterDebugLogger get() {
    return newInstance();
  }

  public static CounterDebugLogger_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static CounterDebugLogger newInstance() {
    return new CounterDebugLogger();
  }

  private static final class InstanceHolder {
    private static final CounterDebugLogger_Factory INSTANCE = new CounterDebugLogger_Factory();
  }
}
