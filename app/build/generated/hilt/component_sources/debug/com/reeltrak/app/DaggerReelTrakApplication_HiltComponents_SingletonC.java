package com.reeltrak.app;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.hilt.work.HiltWorkerFactory;
import androidx.hilt.work.WorkerAssistedFactory;
import androidx.hilt.work.WorkerFactoryModule_ProvideFactoryFactory;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.reeltrak.app.core.ads.RewardedAdManager;
import com.reeltrak.app.core.auth.GoogleAuthManager;
import com.reeltrak.app.core.di.CoroutinesModule_ProvideApplicationScopeFactory;
import com.reeltrak.app.core.di.CoroutinesModule_ProvideDefaultDispatcherFactory;
import com.reeltrak.app.core.di.CoroutinesModule_ProvideIoDispatcherFactory;
import com.reeltrak.app.core.di.DatabaseModule_ProvideDailyReelDataDaoFactory;
import com.reeltrak.app.core.di.DatabaseModule_ProvideLocalRestoreJournalDaoFactory;
import com.reeltrak.app.core.di.DatabaseModule_ProvideReelTrakDatabaseFactory;
import com.reeltrak.app.core.di.FirebaseModule_ProvideFirebaseFirestoreFactory;
import com.reeltrak.app.core.di.NetworkModule_ProvideOkHttpClientFactory;
import com.reeltrak.app.core.di.StateModule_ProvideOverlayStateManagerFactory;
import com.reeltrak.app.core.sync.RestoreAckWorker;
import com.reeltrak.app.core.sync.RestoreAckWorker_AssistedFactory;
import com.reeltrak.app.core.sync.RestoreReconciler;
import com.reeltrak.app.core.sync.SyncManager;
import com.reeltrak.app.core.sync.SyncWorker;
import com.reeltrak.app.core.sync.SyncWorker_AssistedFactory;
import com.reeltrak.app.core.update.InAppUpdateManager;
import com.reeltrak.app.data.local.dao.DailyReelDataDao;
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao;
import com.reeltrak.app.data.local.database.ReelTrakDatabase;
import com.reeltrak.app.data.repository.AccountRepositoryImpl;
import com.reeltrak.app.data.repository.LocalBackupRepositoryImpl;
import com.reeltrak.app.data.repository.LocalRestoreRepositoryImpl;
import com.reeltrak.app.data.repository.OnboardingRepositoryImpl;
import com.reeltrak.app.data.repository.PreferencesRepositoryImpl;
import com.reeltrak.app.data.repository.SubscriptionRepositoryImpl;
import com.reeltrak.app.data.security.SubscriptionTimeAuthority;
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager;
import com.reeltrak.app.data.security.authorization.AppIntegrityManager;
import com.reeltrak.app.data.services.RazorpaySubscriptionPurchaseServiceImpl;
import com.reeltrak.app.domain.managers.SubscriptionManager;
import com.reeltrak.app.domain.repository.SubscriptionRepository;
import com.reeltrak.app.features.auth.LoginViewModel;
import com.reeltrak.app.features.auth.LoginViewModel_HiltModules;
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel;
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel_HiltModules;
import com.reeltrak.app.features.onboarding.viewmodel.OnboardingViewModel;
import com.reeltrak.app.features.onboarding.viewmodel.OnboardingViewModel_HiltModules;
import com.reeltrak.app.features.settings.viewmodel.SettingsViewModel;
import com.reeltrak.app.features.settings.viewmodel.SettingsViewModel_HiltModules;
import com.reeltrak.app.services.ReelTrackerService;
import com.reeltrak.app.services.ReelTrackerService_MembersInjector;
import com.reeltrak.app.services.notifications.ReelNotificationManager;
import com.reeltrak.app.services.overlay.OverlayStateManager;
import com.reeltrak.app.services.tracking.DayManager;
import dagger.hilt.android.ActivityRetainedLifecycle;
import dagger.hilt.android.ViewModelLifecycle;
import dagger.hilt.android.internal.builders.ActivityComponentBuilder;
import dagger.hilt.android.internal.builders.ActivityRetainedComponentBuilder;
import dagger.hilt.android.internal.builders.FragmentComponentBuilder;
import dagger.hilt.android.internal.builders.ServiceComponentBuilder;
import dagger.hilt.android.internal.builders.ViewComponentBuilder;
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder;
import dagger.hilt.android.internal.builders.ViewWithFragmentComponentBuilder;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories_InternalFactoryFactory_Factory;
import dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory;
import dagger.hilt.android.internal.managers.SavedStateHandleHolder;
import dagger.hilt.android.internal.modules.ApplicationContextModule;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideContextFactory;
import dagger.internal.DaggerGenerated;
import dagger.internal.DelegateFactory;
import dagger.internal.DoubleCheck;
import dagger.internal.IdentifierNameString;
import dagger.internal.KeepFieldType;
import dagger.internal.LazyClassKeyMap;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.SingleCheck;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;
import kotlinx.coroutines.CoroutineScope;
import okhttp3.OkHttpClient;

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
public final class DaggerReelTrakApplication_HiltComponents_SingletonC {
  private DaggerReelTrakApplication_HiltComponents_SingletonC() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ApplicationContextModule applicationContextModule;

    private Builder() {
    }

    public Builder applicationContextModule(ApplicationContextModule applicationContextModule) {
      this.applicationContextModule = Preconditions.checkNotNull(applicationContextModule);
      return this;
    }

    public ReelTrakApplication_HiltComponents.SingletonC build() {
      Preconditions.checkBuilderRequirement(applicationContextModule, ApplicationContextModule.class);
      return new SingletonCImpl(applicationContextModule);
    }
  }

  private static final class ActivityRetainedCBuilder implements ReelTrakApplication_HiltComponents.ActivityRetainedC.Builder {
    private final SingletonCImpl singletonCImpl;

    private SavedStateHandleHolder savedStateHandleHolder;

    private ActivityRetainedCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ActivityRetainedCBuilder savedStateHandleHolder(
        SavedStateHandleHolder savedStateHandleHolder) {
      this.savedStateHandleHolder = Preconditions.checkNotNull(savedStateHandleHolder);
      return this;
    }

    @Override
    public ReelTrakApplication_HiltComponents.ActivityRetainedC build() {
      Preconditions.checkBuilderRequirement(savedStateHandleHolder, SavedStateHandleHolder.class);
      return new ActivityRetainedCImpl(singletonCImpl, savedStateHandleHolder);
    }
  }

  private static final class ActivityCBuilder implements ReelTrakApplication_HiltComponents.ActivityC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private Activity activity;

    private ActivityCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ActivityCBuilder activity(Activity activity) {
      this.activity = Preconditions.checkNotNull(activity);
      return this;
    }

    @Override
    public ReelTrakApplication_HiltComponents.ActivityC build() {
      Preconditions.checkBuilderRequirement(activity, Activity.class);
      return new ActivityCImpl(singletonCImpl, activityRetainedCImpl, activity);
    }
  }

  private static final class FragmentCBuilder implements ReelTrakApplication_HiltComponents.FragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private Fragment fragment;

    private FragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public FragmentCBuilder fragment(Fragment fragment) {
      this.fragment = Preconditions.checkNotNull(fragment);
      return this;
    }

    @Override
    public ReelTrakApplication_HiltComponents.FragmentC build() {
      Preconditions.checkBuilderRequirement(fragment, Fragment.class);
      return new FragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragment);
    }
  }

  private static final class ViewWithFragmentCBuilder implements ReelTrakApplication_HiltComponents.ViewWithFragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private View view;

    private ViewWithFragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;
    }

    @Override
    public ViewWithFragmentCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public ReelTrakApplication_HiltComponents.ViewWithFragmentC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewWithFragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl, view);
    }
  }

  private static final class ViewCBuilder implements ReelTrakApplication_HiltComponents.ViewC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private View view;

    private ViewCBuilder(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public ViewCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public ReelTrakApplication_HiltComponents.ViewC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, view);
    }
  }

  private static final class ViewModelCBuilder implements ReelTrakApplication_HiltComponents.ViewModelC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private SavedStateHandle savedStateHandle;

    private ViewModelLifecycle viewModelLifecycle;

    private ViewModelCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ViewModelCBuilder savedStateHandle(SavedStateHandle handle) {
      this.savedStateHandle = Preconditions.checkNotNull(handle);
      return this;
    }

    @Override
    public ViewModelCBuilder viewModelLifecycle(ViewModelLifecycle viewModelLifecycle) {
      this.viewModelLifecycle = Preconditions.checkNotNull(viewModelLifecycle);
      return this;
    }

    @Override
    public ReelTrakApplication_HiltComponents.ViewModelC build() {
      Preconditions.checkBuilderRequirement(savedStateHandle, SavedStateHandle.class);
      Preconditions.checkBuilderRequirement(viewModelLifecycle, ViewModelLifecycle.class);
      return new ViewModelCImpl(singletonCImpl, activityRetainedCImpl, savedStateHandle, viewModelLifecycle);
    }
  }

  private static final class ServiceCBuilder implements ReelTrakApplication_HiltComponents.ServiceC.Builder {
    private final SingletonCImpl singletonCImpl;

    private Service service;

    private ServiceCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ServiceCBuilder service(Service service) {
      this.service = Preconditions.checkNotNull(service);
      return this;
    }

    @Override
    public ReelTrakApplication_HiltComponents.ServiceC build() {
      Preconditions.checkBuilderRequirement(service, Service.class);
      return new ServiceCImpl(singletonCImpl, service);
    }
  }

  private static final class ViewWithFragmentCImpl extends ReelTrakApplication_HiltComponents.ViewWithFragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private final ViewWithFragmentCImpl viewWithFragmentCImpl = this;

    private ViewWithFragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;


    }
  }

  private static final class FragmentCImpl extends ReelTrakApplication_HiltComponents.FragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl = this;

    private FragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        Fragment fragmentParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return activityCImpl.getHiltInternalFactoryFactory();
    }

    @Override
    public ViewWithFragmentComponentBuilder viewWithFragmentComponentBuilder() {
      return new ViewWithFragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl);
    }
  }

  private static final class ViewCImpl extends ReelTrakApplication_HiltComponents.ViewC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final ViewCImpl viewCImpl = this;

    private ViewCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }
  }

  private static final class ActivityCImpl extends ReelTrakApplication_HiltComponents.ActivityC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl = this;

    private ActivityCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, Activity activityParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    @Override
    public void injectMainActivity(MainActivity mainActivity) {
      injectMainActivity2(mainActivity);
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(getViewModelKeys(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return LazyClassKeyMap.<Boolean>of(ImmutableMap.<String, Boolean>of(LazyClassKeyProvider.com_reeltrak_app_features_dashboard_viewmodel_DashboardViewModel, DashboardViewModel_HiltModules.KeyModule.provide(), LazyClassKeyProvider.com_reeltrak_app_features_auth_LoginViewModel, LoginViewModel_HiltModules.KeyModule.provide(), LazyClassKeyProvider.com_reeltrak_app_features_onboarding_viewmodel_OnboardingViewModel, OnboardingViewModel_HiltModules.KeyModule.provide(), LazyClassKeyProvider.com_reeltrak_app_features_settings_viewmodel_SettingsViewModel, SettingsViewModel_HiltModules.KeyModule.provide()));
    }

    @Override
    public ViewModelComponentBuilder getViewModelComponentBuilder() {
      return new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public FragmentComponentBuilder fragmentComponentBuilder() {
      return new FragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public ViewComponentBuilder viewComponentBuilder() {
      return new ViewCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @CanIgnoreReturnValue
    private MainActivity injectMainActivity2(MainActivity instance) {
      MainActivity_MembersInjector.injectSubscriptionManager(instance, DoubleCheck.lazy(singletonCImpl.subscriptionManagerProvider));
      MainActivity_MembersInjector.injectRazorpayPurchaseService(instance, singletonCImpl.razorpaySubscriptionPurchaseServiceImplProvider.get());
      MainActivity_MembersInjector.injectRewardedAdManager(instance, DoubleCheck.lazy(singletonCImpl.rewardedAdManagerProvider));
      MainActivity_MembersInjector.injectInAppUpdateManager(instance, singletonCImpl.inAppUpdateManagerProvider.get());
      MainActivity_MembersInjector.injectPreferencesRepository(instance, singletonCImpl.preferencesRepositoryImplProvider.get());
      MainActivity_MembersInjector.injectDayManager(instance, DoubleCheck.lazy(singletonCImpl.dayManagerProvider));
      return instance;
    }

    @IdentifierNameString
    private static final class LazyClassKeyProvider {
      static String com_reeltrak_app_features_dashboard_viewmodel_DashboardViewModel = "com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel";

      static String com_reeltrak_app_features_settings_viewmodel_SettingsViewModel = "com.reeltrak.app.features.settings.viewmodel.SettingsViewModel";

      static String com_reeltrak_app_features_auth_LoginViewModel = "com.reeltrak.app.features.auth.LoginViewModel";

      static String com_reeltrak_app_features_onboarding_viewmodel_OnboardingViewModel = "com.reeltrak.app.features.onboarding.viewmodel.OnboardingViewModel";

      @KeepFieldType
      DashboardViewModel com_reeltrak_app_features_dashboard_viewmodel_DashboardViewModel2;

      @KeepFieldType
      SettingsViewModel com_reeltrak_app_features_settings_viewmodel_SettingsViewModel2;

      @KeepFieldType
      LoginViewModel com_reeltrak_app_features_auth_LoginViewModel2;

      @KeepFieldType
      OnboardingViewModel com_reeltrak_app_features_onboarding_viewmodel_OnboardingViewModel2;
    }
  }

  private static final class ViewModelCImpl extends ReelTrakApplication_HiltComponents.ViewModelC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    private Provider<DashboardViewModel> dashboardViewModelProvider;

    private Provider<LoginViewModel> loginViewModelProvider;

    private Provider<OnboardingViewModel> onboardingViewModelProvider;

    private Provider<SettingsViewModel> settingsViewModelProvider;

    private ViewModelCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, SavedStateHandle savedStateHandleParam,
        ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;

      initialize(savedStateHandleParam, viewModelLifecycleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandle savedStateHandleParam,
        final ViewModelLifecycle viewModelLifecycleParam) {
      this.dashboardViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 0);
      this.loginViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 1);
      this.onboardingViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 2);
      this.settingsViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 3);
    }

    @Override
    public Map<Class<?>, javax.inject.Provider<ViewModel>> getHiltViewModelMap() {
      return LazyClassKeyMap.<javax.inject.Provider<ViewModel>>of(ImmutableMap.<String, javax.inject.Provider<ViewModel>>of(LazyClassKeyProvider.com_reeltrak_app_features_dashboard_viewmodel_DashboardViewModel, ((Provider) dashboardViewModelProvider), LazyClassKeyProvider.com_reeltrak_app_features_auth_LoginViewModel, ((Provider) loginViewModelProvider), LazyClassKeyProvider.com_reeltrak_app_features_onboarding_viewmodel_OnboardingViewModel, ((Provider) onboardingViewModelProvider), LazyClassKeyProvider.com_reeltrak_app_features_settings_viewmodel_SettingsViewModel, ((Provider) settingsViewModelProvider)));
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return ImmutableMap.<Class<?>, Object>of();
    }

    @IdentifierNameString
    private static final class LazyClassKeyProvider {
      static String com_reeltrak_app_features_dashboard_viewmodel_DashboardViewModel = "com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel";

      static String com_reeltrak_app_features_settings_viewmodel_SettingsViewModel = "com.reeltrak.app.features.settings.viewmodel.SettingsViewModel";

      static String com_reeltrak_app_features_auth_LoginViewModel = "com.reeltrak.app.features.auth.LoginViewModel";

      static String com_reeltrak_app_features_onboarding_viewmodel_OnboardingViewModel = "com.reeltrak.app.features.onboarding.viewmodel.OnboardingViewModel";

      @KeepFieldType
      DashboardViewModel com_reeltrak_app_features_dashboard_viewmodel_DashboardViewModel2;

      @KeepFieldType
      SettingsViewModel com_reeltrak_app_features_settings_viewmodel_SettingsViewModel2;

      @KeepFieldType
      LoginViewModel com_reeltrak_app_features_auth_LoginViewModel2;

      @KeepFieldType
      OnboardingViewModel com_reeltrak_app_features_onboarding_viewmodel_OnboardingViewModel2;
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final ViewModelCImpl viewModelCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          ViewModelCImpl viewModelCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.viewModelCImpl = viewModelCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel 
          return (T) new DashboardViewModel(singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.provideDailyReelDataDaoProvider.get(), singletonCImpl.provideOverlayStateManagerProvider.get(), singletonCImpl.dayManagerProvider.get(), singletonCImpl.reelNotificationManagerProvider.get(), singletonCImpl.subscriptionManagerProvider.get(), singletonCImpl.rewardedAdManagerProvider.get(), singletonCImpl.appAuthorizationManagerProvider.get());

          case 1: // com.reeltrak.app.features.auth.LoginViewModel 
          return (T) new LoginViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.googleAuthManagerProvider.get(), singletonCImpl.accountRepositoryImplProvider.get(), singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.syncManagerProvider.get(), singletonCImpl.subscriptionManagerProvider.get(), singletonCImpl.provideReelTrakDatabaseProvider.get(), singletonCImpl.dayManagerProvider.get(), singletonCImpl.provideApplicationScopeProvider.get(), CoroutinesModule_ProvideIoDispatcherFactory.provideIoDispatcher());

          case 2: // com.reeltrak.app.features.onboarding.viewmodel.OnboardingViewModel 
          return (T) new OnboardingViewModel(singletonCImpl.preferencesRepositoryImplProvider.get(), DoubleCheck.lazy(singletonCImpl.appAuthorizationManagerProvider));

          case 3: // com.reeltrak.app.features.settings.viewmodel.SettingsViewModel 
          return (T) new SettingsViewModel(singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.accountRepositoryImplProvider.get(), singletonCImpl.onboardingRepositoryImplProvider.get(), singletonCImpl.subscriptionManagerProvider.get(), singletonCImpl.razorpaySubscriptionPurchaseServiceImplProvider.get(), singletonCImpl.dayManagerProvider.get(), singletonCImpl.syncManagerProvider.get(), singletonCImpl.appAuthorizationManagerProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ActivityRetainedCImpl extends ReelTrakApplication_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    private Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

    private ActivityRetainedCImpl(SingletonCImpl singletonCImpl,
        SavedStateHandleHolder savedStateHandleHolderParam) {
      this.singletonCImpl = singletonCImpl;

      initialize(savedStateHandleHolderParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandleHolder savedStateHandleHolderParam) {
      this.provideActivityRetainedLifecycleProvider = DoubleCheck.provider(new SwitchingProvider<ActivityRetainedLifecycle>(singletonCImpl, activityRetainedCImpl, 0));
    }

    @Override
    public ActivityComponentBuilder activityComponentBuilder() {
      return new ActivityCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public ActivityRetainedLifecycle getActivityRetainedLifecycle() {
      return provideActivityRetainedLifecycleProvider.get();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // dagger.hilt.android.ActivityRetainedLifecycle 
          return (T) ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory.provideActivityRetainedLifecycle();

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ServiceCImpl extends ReelTrakApplication_HiltComponents.ServiceC {
    private final SingletonCImpl singletonCImpl;

    private final ServiceCImpl serviceCImpl = this;

    private ServiceCImpl(SingletonCImpl singletonCImpl, Service serviceParam) {
      this.singletonCImpl = singletonCImpl;


    }

    @Override
    public void injectReelTrackerService(ReelTrackerService reelTrackerService) {
      injectReelTrackerService2(reelTrackerService);
    }

    @CanIgnoreReturnValue
    private ReelTrackerService injectReelTrackerService2(ReelTrackerService instance) {
      ReelTrackerService_MembersInjector.injectPreferencesRepository(instance, singletonCImpl.preferencesRepositoryImplProvider.get());
      ReelTrackerService_MembersInjector.injectDayManager(instance, singletonCImpl.dayManagerProvider.get());
      ReelTrackerService_MembersInjector.injectSyncManager(instance, singletonCImpl.syncManagerProvider.get());
      ReelTrackerService_MembersInjector.injectSubscriptionManager(instance, singletonCImpl.subscriptionManagerProvider.get());
      ReelTrackerService_MembersInjector.injectAccountRepository(instance, singletonCImpl.accountRepositoryImplProvider.get());
      ReelTrackerService_MembersInjector.injectAppAuthorizationManager(instance, singletonCImpl.appAuthorizationManagerProvider.get());
      ReelTrackerService_MembersInjector.injectInAppUpdateManager(instance, singletonCImpl.inAppUpdateManagerProvider.get());
      ReelTrackerService_MembersInjector.injectOverlayStateManager(instance, singletonCImpl.provideOverlayStateManagerProvider.get());
      return instance;
    }
  }

  private static final class SingletonCImpl extends ReelTrakApplication_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    private Provider<ReelTrakDatabase> provideReelTrakDatabaseProvider;

    private Provider<LocalRestoreJournalDao> provideLocalRestoreJournalDaoProvider;

    private Provider<OkHttpClient> provideOkHttpClientProvider;

    private Provider<RestoreAckWorker_AssistedFactory> restoreAckWorker_AssistedFactoryProvider;

    private Provider<FirebaseFirestore> provideFirebaseFirestoreProvider;

    private Provider<DailyReelDataDao> provideDailyReelDataDaoProvider;

    private Provider<AccountRepositoryImpl> accountRepositoryImplProvider;

    private Provider<SubscriptionTimeAuthority> subscriptionTimeAuthorityProvider;

    private Provider<SubscriptionRepositoryImpl> subscriptionRepositoryImplProvider;

    private Provider<AppIntegrityManager> appIntegrityManagerProvider;

    private Provider<AppAuthorizationManager> appAuthorizationManagerProvider;

    private Provider<SubscriptionManager> subscriptionManagerProvider;

    private Provider<SyncWorker_AssistedFactory> syncWorker_AssistedFactoryProvider;

    private Provider<RazorpaySubscriptionPurchaseServiceImpl> razorpaySubscriptionPurchaseServiceImplProvider;

    private Provider<RewardedAdManager> rewardedAdManagerProvider;

    private Provider<InAppUpdateManager> inAppUpdateManagerProvider;

    private Provider<PreferencesRepositoryImpl> preferencesRepositoryImplProvider;

    private Provider<ReelNotificationManager> reelNotificationManagerProvider;

    private Provider<DayManager> dayManagerProvider;

    private Provider<LocalBackupRepositoryImpl> localBackupRepositoryImplProvider;

    private Provider<LocalRestoreRepositoryImpl> localRestoreRepositoryImplProvider;

    private Provider<RestoreReconciler> restoreReconcilerProvider;

    private Provider<CoroutineScope> provideApplicationScopeProvider;

    private Provider<SyncManager> syncManagerProvider;

    private Provider<OverlayStateManager> provideOverlayStateManagerProvider;

    private Provider<GoogleAuthManager> googleAuthManagerProvider;

    private Provider<OnboardingRepositoryImpl> onboardingRepositoryImplProvider;

    private SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);

    }

    private Map<String, javax.inject.Provider<WorkerAssistedFactory<? extends ListenableWorker>>> mapOfStringAndProviderOfWorkerAssistedFactoryOf(
        ) {
      return ImmutableMap.<String, javax.inject.Provider<WorkerAssistedFactory<? extends ListenableWorker>>>of("com.reeltrak.app.core.sync.RestoreAckWorker", ((Provider) restoreAckWorker_AssistedFactoryProvider), "com.reeltrak.app.core.sync.SyncWorker", ((Provider) syncWorker_AssistedFactoryProvider));
    }

    private HiltWorkerFactory hiltWorkerFactory() {
      return WorkerFactoryModule_ProvideFactoryFactory.provideFactory(mapOfStringAndProviderOfWorkerAssistedFactoryOf());
    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.provideReelTrakDatabaseProvider = DoubleCheck.provider(new SwitchingProvider<ReelTrakDatabase>(singletonCImpl, 2));
      this.provideLocalRestoreJournalDaoProvider = DoubleCheck.provider(new SwitchingProvider<LocalRestoreJournalDao>(singletonCImpl, 1));
      this.provideOkHttpClientProvider = DoubleCheck.provider(new SwitchingProvider<OkHttpClient>(singletonCImpl, 3));
      this.restoreAckWorker_AssistedFactoryProvider = SingleCheck.provider(new SwitchingProvider<RestoreAckWorker_AssistedFactory>(singletonCImpl, 0));
      this.provideFirebaseFirestoreProvider = DoubleCheck.provider(new SwitchingProvider<FirebaseFirestore>(singletonCImpl, 5));
      this.provideDailyReelDataDaoProvider = DoubleCheck.provider(new SwitchingProvider<DailyReelDataDao>(singletonCImpl, 6));
      this.accountRepositoryImplProvider = DoubleCheck.provider(new SwitchingProvider<AccountRepositoryImpl>(singletonCImpl, 7));
      this.subscriptionTimeAuthorityProvider = DoubleCheck.provider(new SwitchingProvider<SubscriptionTimeAuthority>(singletonCImpl, 10));
      this.subscriptionRepositoryImplProvider = DoubleCheck.provider(new SwitchingProvider<SubscriptionRepositoryImpl>(singletonCImpl, 9));
      this.appIntegrityManagerProvider = DoubleCheck.provider(new SwitchingProvider<AppIntegrityManager>(singletonCImpl, 12));
      this.appAuthorizationManagerProvider = DoubleCheck.provider(new SwitchingProvider<AppAuthorizationManager>(singletonCImpl, 11));
      this.subscriptionManagerProvider = DoubleCheck.provider(new SwitchingProvider<SubscriptionManager>(singletonCImpl, 8));
      this.syncWorker_AssistedFactoryProvider = SingleCheck.provider(new SwitchingProvider<SyncWorker_AssistedFactory>(singletonCImpl, 4));
      this.razorpaySubscriptionPurchaseServiceImplProvider = DoubleCheck.provider(new SwitchingProvider<RazorpaySubscriptionPurchaseServiceImpl>(singletonCImpl, 13));
      this.rewardedAdManagerProvider = DoubleCheck.provider(new SwitchingProvider<RewardedAdManager>(singletonCImpl, 14));
      this.inAppUpdateManagerProvider = DoubleCheck.provider(new SwitchingProvider<InAppUpdateManager>(singletonCImpl, 15));
      this.preferencesRepositoryImplProvider = DoubleCheck.provider(new SwitchingProvider<PreferencesRepositoryImpl>(singletonCImpl, 16));
      this.reelNotificationManagerProvider = DoubleCheck.provider(new SwitchingProvider<ReelNotificationManager>(singletonCImpl, 18));
      this.dayManagerProvider = new DelegateFactory<>();
      this.localBackupRepositoryImplProvider = DoubleCheck.provider(new SwitchingProvider<LocalBackupRepositoryImpl>(singletonCImpl, 20));
      this.localRestoreRepositoryImplProvider = DoubleCheck.provider(new SwitchingProvider<LocalRestoreRepositoryImpl>(singletonCImpl, 21));
      this.restoreReconcilerProvider = DoubleCheck.provider(new SwitchingProvider<RestoreReconciler>(singletonCImpl, 22));
      this.provideApplicationScopeProvider = DoubleCheck.provider(new SwitchingProvider<CoroutineScope>(singletonCImpl, 23));
      this.syncManagerProvider = DoubleCheck.provider(new SwitchingProvider<SyncManager>(singletonCImpl, 19));
      DelegateFactory.setDelegate(dayManagerProvider, DoubleCheck.provider(new SwitchingProvider<DayManager>(singletonCImpl, 17)));
      this.provideOverlayStateManagerProvider = DoubleCheck.provider(new SwitchingProvider<OverlayStateManager>(singletonCImpl, 24));
      this.googleAuthManagerProvider = DoubleCheck.provider(new SwitchingProvider<GoogleAuthManager>(singletonCImpl, 25));
      this.onboardingRepositoryImplProvider = DoubleCheck.provider(new SwitchingProvider<OnboardingRepositoryImpl>(singletonCImpl, 26));
    }

    @Override
    public void injectReelTrakApplication(ReelTrakApplication reelTrakApplication) {
      injectReelTrakApplication2(reelTrakApplication);
    }

    @Override
    public Set<Boolean> getDisableFragmentGetContextFix() {
      return ImmutableSet.<Boolean>of();
    }

    @Override
    public ActivityRetainedComponentBuilder retainedComponentBuilder() {
      return new ActivityRetainedCBuilder(singletonCImpl);
    }

    @Override
    public ServiceComponentBuilder serviceComponentBuilder() {
      return new ServiceCBuilder(singletonCImpl);
    }

    @CanIgnoreReturnValue
    private ReelTrakApplication injectReelTrakApplication2(ReelTrakApplication instance) {
      ReelTrakApplication_MembersInjector.injectWorkerFactory(instance, hiltWorkerFactory());
      return instance;
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.reeltrak.app.core.sync.RestoreAckWorker_AssistedFactory 
          return (T) new RestoreAckWorker_AssistedFactory() {
            @Override
            public RestoreAckWorker create(Context appContext, WorkerParameters workerParams) {
              return new RestoreAckWorker(appContext, workerParams, singletonCImpl.provideLocalRestoreJournalDaoProvider.get(), singletonCImpl.provideOkHttpClientProvider.get());
            }
          };

          case 1: // com.reeltrak.app.data.local.dao.LocalRestoreJournalDao 
          return (T) DatabaseModule_ProvideLocalRestoreJournalDaoFactory.provideLocalRestoreJournalDao(singletonCImpl.provideReelTrakDatabaseProvider.get());

          case 2: // com.reeltrak.app.data.local.database.ReelTrakDatabase 
          return (T) DatabaseModule_ProvideReelTrakDatabaseFactory.provideReelTrakDatabase(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 3: // okhttp3.OkHttpClient 
          return (T) NetworkModule_ProvideOkHttpClientFactory.provideOkHttpClient();

          case 4: // com.reeltrak.app.core.sync.SyncWorker_AssistedFactory 
          return (T) new SyncWorker_AssistedFactory() {
            @Override
            public SyncWorker create(Context appContext2, WorkerParameters workerParams2) {
              return new SyncWorker(appContext2, workerParams2, singletonCImpl.provideFirebaseFirestoreProvider.get(), singletonCImpl.provideDailyReelDataDaoProvider.get(), singletonCImpl.accountRepositoryImplProvider.get(), singletonCImpl.subscriptionManagerProvider.get());
            }
          };

          case 5: // com.google.firebase.firestore.FirebaseFirestore 
          return (T) FirebaseModule_ProvideFirebaseFirestoreFactory.provideFirebaseFirestore();

          case 6: // com.reeltrak.app.data.local.dao.DailyReelDataDao 
          return (T) DatabaseModule_ProvideDailyReelDataDaoFactory.provideDailyReelDataDao(singletonCImpl.provideReelTrakDatabaseProvider.get());

          case 7: // com.reeltrak.app.data.repository.AccountRepositoryImpl 
          return (T) new AccountRepositoryImpl(singletonCImpl.provideFirebaseFirestoreProvider.get(), singletonCImpl.provideOkHttpClientProvider.get());

          case 8: // com.reeltrak.app.domain.managers.SubscriptionManager 
          return (T) new SubscriptionManager((SubscriptionRepository) ((Provider) singletonCImpl.subscriptionRepositoryImplProvider).get(), singletonCImpl.subscriptionTimeAuthorityProvider.get(), singletonCImpl.appAuthorizationManagerProvider.get());

          case 9: // com.reeltrak.app.data.repository.SubscriptionRepositoryImpl 
          return (T) new SubscriptionRepositoryImpl(singletonCImpl.provideFirebaseFirestoreProvider.get(), singletonCImpl.subscriptionTimeAuthorityProvider.get(), singletonCImpl.provideOkHttpClientProvider.get());

          case 10: // com.reeltrak.app.data.security.SubscriptionTimeAuthority 
          return (T) new SubscriptionTimeAuthority(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideOkHttpClientProvider.get());

          case 11: // com.reeltrak.app.data.security.authorization.AppAuthorizationManager 
          return (T) new AppAuthorizationManager((SubscriptionRepository) ((Provider) singletonCImpl.subscriptionRepositoryImplProvider).get(), singletonCImpl.subscriptionTimeAuthorityProvider.get(), singletonCImpl.appIntegrityManagerProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 12: // com.reeltrak.app.data.security.authorization.AppIntegrityManager 
          return (T) new AppIntegrityManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 13: // com.reeltrak.app.data.services.RazorpaySubscriptionPurchaseServiceImpl 
          return (T) new RazorpaySubscriptionPurchaseServiceImpl(DoubleCheck.lazy(((Provider) singletonCImpl.subscriptionRepositoryImplProvider)), singletonCImpl.provideOkHttpClientProvider.get());

          case 14: // com.reeltrak.app.core.ads.RewardedAdManager 
          return (T) new RewardedAdManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 15: // com.reeltrak.app.core.update.InAppUpdateManager 
          return (T) new InAppUpdateManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 16: // com.reeltrak.app.data.repository.PreferencesRepositoryImpl 
          return (T) new PreferencesRepositoryImpl(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), DoubleCheck.lazy(singletonCImpl.appAuthorizationManagerProvider));

          case 17: // com.reeltrak.app.services.tracking.DayManager 
          return (T) new DayManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideDailyReelDataDaoProvider.get(), singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.reelNotificationManagerProvider.get(), singletonCImpl.syncManagerProvider.get(), singletonCImpl.appAuthorizationManagerProvider.get(), DoubleCheck.lazy(singletonCImpl.subscriptionManagerProvider));

          case 18: // com.reeltrak.app.services.notifications.ReelNotificationManager 
          return (T) new ReelNotificationManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.preferencesRepositoryImplProvider.get());

          case 19: // com.reeltrak.app.core.sync.SyncManager 
          return (T) new SyncManager(singletonCImpl.provideFirebaseFirestoreProvider.get(), singletonCImpl.provideDailyReelDataDaoProvider.get(), singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.accountRepositoryImplProvider.get(), DoubleCheck.lazy(singletonCImpl.dayManagerProvider), DoubleCheck.lazy(singletonCImpl.subscriptionManagerProvider), singletonCImpl.localBackupRepositoryImplProvider.get(), singletonCImpl.localRestoreRepositoryImplProvider.get(), singletonCImpl.restoreReconcilerProvider.get(), singletonCImpl.provideApplicationScopeProvider.get(), CoroutinesModule_ProvideIoDispatcherFactory.provideIoDispatcher(), singletonCImpl.provideOkHttpClientProvider.get());

          case 20: // com.reeltrak.app.data.repository.LocalBackupRepositoryImpl 
          return (T) new LocalBackupRepositoryImpl(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideDailyReelDataDaoProvider.get(), singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.accountRepositoryImplProvider.get(), DoubleCheck.lazy(singletonCImpl.subscriptionManagerProvider), CoroutinesModule_ProvideIoDispatcherFactory.provideIoDispatcher(), singletonCImpl.provideOkHttpClientProvider.get());

          case 21: // com.reeltrak.app.data.repository.LocalRestoreRepositoryImpl 
          return (T) new LocalRestoreRepositoryImpl(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideReelTrakDatabaseProvider.get(), singletonCImpl.provideDailyReelDataDaoProvider.get(), singletonCImpl.provideLocalRestoreJournalDaoProvider.get(), singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.accountRepositoryImplProvider.get(), DoubleCheck.lazy(singletonCImpl.subscriptionManagerProvider), CoroutinesModule_ProvideIoDispatcherFactory.provideIoDispatcher(), singletonCImpl.provideOkHttpClientProvider.get());

          case 22: // com.reeltrak.app.core.sync.RestoreReconciler 
          return (T) new RestoreReconciler(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideLocalRestoreJournalDaoProvider.get(), singletonCImpl.preferencesRepositoryImplProvider.get());

          case 23: // @com.reeltrak.app.core.di.ApplicationScope kotlinx.coroutines.CoroutineScope 
          return (T) CoroutinesModule_ProvideApplicationScopeFactory.provideApplicationScope(CoroutinesModule_ProvideDefaultDispatcherFactory.provideDefaultDispatcher());

          case 24: // com.reeltrak.app.services.overlay.OverlayStateManager 
          return (T) StateModule_ProvideOverlayStateManagerFactory.provideOverlayStateManager();

          case 25: // com.reeltrak.app.core.auth.GoogleAuthManager 
          return (T) new GoogleAuthManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 26: // com.reeltrak.app.data.repository.OnboardingRepositoryImpl 
          return (T) new OnboardingRepositoryImpl(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideFirebaseFirestoreProvider.get(), singletonCImpl.preferencesRepositoryImplProvider.get(), singletonCImpl.accountRepositoryImplProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
