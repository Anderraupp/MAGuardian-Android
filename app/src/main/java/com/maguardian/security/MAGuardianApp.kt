package com.maguardian.security

import android.app.Application
import com.facebook.appevents.AppEventsLogger
import com.tiktok.TikTokBusinessSdk

class MAGuardianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.maguardian.security.service.AppUpdateCheckJobService.schedule(this)
        com.maguardian.security.data.ThreatCatalog.initialize(this)
        com.maguardian.security.data.ThreatCatalogUpdater.schedule(this)
        com.maguardian.security.data.ThreatCatalogUpdater.update(this)
        AppEventsLogger.activateApp(this)

        val tiktokConfig = TikTokBusinessSdk.TTConfig(applicationContext)
            .setAppId("7639159884537266194")
        TikTokBusinessSdk.initializeSdk(tiktokConfig)
    }
}
