package com.ailm.android.startup

import android.app.Application

class AilmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Avoid opening the 300K-artwork SQLite/Fusion database while Android
        // is still drawing its mandatory native launch splash. MainActivity
        // starts the existing bootstrap after the first Compose frame.
    }
}
