package com.openshield

import android.app.Application
import com.openshield.data.BundledSpamImporter
import com.openshield.worker.WifiSyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OpenShieldApp : Application() {

    @Inject
    lateinit var wifiSyncManager: WifiSyncManager

    override fun onCreate() {
        super.onCreate()
        // Wi-Fi'ye her bağlanınca consent kontrolü yapar, uygunsa sync çalışır.
        wifiSyncManager.register()

        // Yerel başlangıç spam listesini içeri aktar (sadece ilk kurulumda/güncellemede)
        CoroutineScope(Dispatchers.IO).launch {
            BundledSpamImporter.importIfNeeded(this@OpenShieldApp)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        wifiSyncManager.unregister()
    }
}
