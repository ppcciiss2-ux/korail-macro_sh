package com.korailmacro.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.korailmacro.app.korail.TrainType

class Prefs(context: Context) {
    var draft: ReservationOptions
        get() = ReservationOptions.fromJson(sp.getString("draft", "").orEmpty()) ?: ReservationOptions(
            departure = depStation, arrival = arrStation,
            date = travelDate.ifBlank { java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) },
            start = startTime, end = endTime, adults = adultCount, seat = seatType, types = trainTypes)
        set(value) { sp.edit().putString("draft", value.json()).apply() }

    var lastResult: String
        get() = sp.getString("lastResult", "").orEmpty()
        // Persist the booking before attempting any notification or suspending the coroutine.
        set(value) { check(sp.edit().putString("lastResult", value).commit()) { "예약 결과 저장 실패" } }

    var lastRunStatus: String
        get() = sp.getString("lastRunStatus", "시작 대기").orEmpty()
        set(value) { sp.edit().putString("lastRunStatus", value).apply() }

    var loginVerified: Boolean
        get() = sp.getBoolean("loginVerified", false)
        set(value) { sp.edit().putBoolean("loginVerified", value).apply() }

    fun favorites(): List<Pair<String, ReservationOptions>> = runCatching {
        val array = org.json.JSONArray(sp.getString("favorites", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val entry = array.getJSONObject(index)
            ReservationOptions.fromJson(entry.getString("options"))?.let { entry.getString("name") to it }
        }
    }.getOrDefault(emptyList())

    fun saveFavorite(name: String, options: ReservationOptions) {
        val values = favorites().filterNot { it.first == name }.toMutableList()
        values.add(0, name to options.copy(onlySelected = false, trainKeys = emptySet()))
        sp.edit().putString("favorites", org.json.JSONArray(values.take(10).map {
            org.json.JSONObject().put("name", it.first).put("options", it.second.json())
        }).toString()).apply()
    }

    fun removeFavorite(name: String) {
        sp.edit().putString("favorites", org.json.JSONArray(favorites().filterNot { it.first == name }.map {
            org.json.JSONObject().put("name", it.first).put("options", it.second.json())
        }).toString()).apply()
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "korail_macro_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var loginType: String
        get() = sp.getString("loginType", "5") ?: "5"
        set(value) = sp.edit().putString("loginType", value).apply()

    var loginId: String
        get() = sp.getString("loginId", "") ?: ""
        set(value) = sp.edit().putString("loginId", value).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(value) = sp.edit().putString("password", value).apply()

    var depStation: String
        get() = sp.getString("depStation", "") ?: ""
        set(value) = sp.edit().putString("depStation", value).apply()

    var arrStation: String
        get() = sp.getString("arrStation", "") ?: ""
        set(value) = sp.edit().putString("arrStation", value).apply()

    var travelDate: String
        get() = sp.getString("travelDate", "") ?: ""
        set(value) = sp.edit().putString("travelDate", value).apply()

    var startTime: String
        get() = sp.getString("startTime", "000000") ?: "000000"
        set(value) = sp.edit().putString("startTime", value).apply()

    var endTime: String
        get() = sp.getString("endTime", "") ?: ""
        set(value) = sp.edit().putString("endTime", value).apply()

    var adultCount: Int
        get() = sp.getInt("adultCount", 1)
        set(value) = sp.edit().putInt("adultCount", value).apply()

    var seatType: String
        get() = sp.getString("seatType", "1") ?: "1"
        set(value) = sp.edit().putString("seatType", value).apply()

    var pollIntervalSec: Int
        get() = sp.getInt("pollIntervalSec", 5)
        set(value) = sp.edit().putInt("pollIntervalSec", value).apply()

    var telegramToken: String
        get() = sp.getString("telegramToken", "") ?: ""
        set(value) = sp.edit().putString("telegramToken", value).apply()

    var telegramChatId: String
        get() = sp.getString("telegramChatId", "") ?: ""
        set(value) = sp.edit().putString("telegramChatId", value).apply()

    private var recentRoutesRaw: String
        get() = sp.getString("recentRoutes", "") ?: ""
        set(value) = sp.edit().putString("recentRoutes", value).apply()

    /** Most-recently-used dep/arr pairs, newest first — shown as quick-pick chips in the station picker. */
    fun recentRoutes(): List<Pair<String, String>> =
        recentRoutesRaw.split("|").filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split(">")
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
        }

    private var trainTypesRaw: String
        get() = sp.getString("trainTypes", TrainType.entries.joinToString(",") { it.name }) ?: ""
        set(value) = sp.edit().putString("trainTypes", value).apply()

    var trainTypes: Set<TrainType>
        get() = trainTypesRaw.split(",").mapNotNull { name -> TrainType.entries.find { it.name == name } }.toSet()
        set(value) {
            trainTypesRaw = value.joinToString(",") { it.name }
        }

    fun pushRecentRoute(dep: String, arr: String) {
        val updated = recentRoutes().toMutableList()
        updated.removeAll { it.first == dep && it.second == arr }
        updated.add(0, dep to arr)
        recentRoutesRaw = updated.take(5).joinToString("|") { "${it.first}>${it.second}" }
    }
}
