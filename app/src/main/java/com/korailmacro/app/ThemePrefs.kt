package com.korailmacro.app

import android.content.Context

/** Plain (unencrypted) prefs for the dark-mode toggle — read at Application startup, before Prefs' EncryptedSharedPreferences/MasterKey would be ready. */
object ThemePrefs {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_MODE = "darkMode"

    fun mode(context: Context): Int {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getInt("mode", if (p.getBoolean(KEY_DARK_MODE, true))
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
    }

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt("mode", mode).apply()
    }

    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DARK_MODE, true)

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }
}
