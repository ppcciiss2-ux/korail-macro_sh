package com.korailmacro.app.korail

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.util.UUID

internal object KorailDevice {
    fun facts(context: Context): DeviceFacts {
        val settingsId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val prefs = context.getSharedPreferences("connection_device", Context.MODE_PRIVATE)
        val id = if (settingsId.matches(Regex("[0-9a-fA-F]{16}"))) settingsId else {
            prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16)
                .also { check(prefs.edit().putString("installation_id", it).commit()) }
        }
        return DeviceFacts(Build.VERSION.RELEASE, Build.MODEL, Build.MANUFACTURER, Build.BOARD,
            Build.VERSION.SDK_INT, Build.VERSION.SDK_INT >= 28 &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE), id)
    }
}
