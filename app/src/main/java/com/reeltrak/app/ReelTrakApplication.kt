package com.reeltrak.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ReelTrakApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            try {
                val debugFactoryClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                val getInstanceMethod = debugFactoryClass.getMethod("getInstance")
                val factory = getInstanceMethod.invoke(null) as com.google.firebase.appcheck.AppCheckProviderFactory
                appCheck.installAppCheckProviderFactory(factory)
                appCheck.setTokenAutoRefreshEnabled(true)
            } catch (e: Exception) {
                android.util.Log.w("ReelTrakApp", "Debug AppCheck provider initialization skipped", e)
            }
        } else {
            try {
                appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
                appCheck.setTokenAutoRefreshEnabled(true)
            } catch (e: Exception) {
                android.util.Log.e("ReelTrakApp", "Play Integrity AppCheck provider initialization failed", e)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
