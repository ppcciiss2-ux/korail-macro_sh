package com.korailmacro.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class KorailMacroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            ThemePrefs.mode(this)
        )
    }
}
