package com.maguardian.security

import android.app.Application
import com.facebook.appevents.AppEventsLogger

class MAGuardianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppEventsLogger.activateApp(this)
    }
}
