package com.maguardian.security

import android.app.Application
import com.facebook.appevents.AppEventsLogger
import com.tiktok.appevents.TikTokBusinessSdk

class MAGuardianApp : Application() {

    override fun onCreate() {
        super.onCreate()

        AppEventsLogger.activateApp(this)

        val tiktokConfig = TikTokBusinessSdk.TTConfig(applicationContext)
            .setAppId("7639159884537266194")
            .setAccessToken("TTnIwdaW9PIUIhjzcdfFYRwH7yKEDQYL")
        TikTokBusinessSdk.initializeSdk(tiktokConfig)
    }
}
