package com.waz.saathi

import android.app.Application

class SaathiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Building the app list once at startup keeps the first voice command
        // from stalling while the package manager is queried.
        runCatching { AppIndex.refresh(this) }
    }
}
