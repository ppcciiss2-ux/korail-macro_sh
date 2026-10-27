package com.korailmacro.app

import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.korail.Train
import com.korailmacro.app.korail.TrainType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Immutable input shared by the form, the read-only query and a single service run. */
data class ReservationOptions(
    val departure: String = "",
    val arrival: String = "",
    val date: String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
    val start: String = "000000",
    val end: String = "",
    val adults: Int = 1,
    val seat: String = KorailApi.SEAT_GENERAL,
    val types: Set<TrainType> = TrainType.entries.toSet(),
    val stopAfterMinutes: Int = 0,
    val stopAt: String = "",
    val onlySelected: Boolean = false,
    val trainKeys: Set<String> = emptySet()
) {
    /** Clamp expired draft fields to the device's current minute, never an active run. */
    fun refreshed(now: LocalDateTime, running: Boolean = false): ReservationOptions {
        if (running) return this
        val day = runCatching { LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return this
        if (day.isAfter(now.toLocalDate())) return this
        val current = now.toLocalTime().withSecond(0).withNano(0)
        val clock = current.format(DateTimeFormatter.ofPattern("HHmmss"))
        fun clamp(value: String) = if (parseTime(value)?.isBefore(current) == true) clock else value
        val updated = copy(date = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE),
            start = clamp(start), end = clamp(end))
        return if (updated == this) this else updated.copy(onlySelected = false, trainKeys = emptySet())
    }

    fun error(now: LocalDateTime = LocalDateTime.now(), checkSelection: Boolean = true, checkPast: Boolean = true): String? {
        if (departure.isBlank() || arrival.isBlank()) return "출발역과 도착역을 선택하세요."
        if (departure == arrival) return "출발역과 도착역을 다르게 선택하세요."
        val day = runCatching { LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            ?: return "가는 날을 선택하세요."
        val from = parseTime(start) ?: return "열차 출발 시작시각을 선택하세요."
        if (checkPast && day.atTime(from).isBefore(now.withSecond(0).withNano(0))) return "이미 지난 출발시각입니다. 날짜와 시간을 다시 선택하세요."
        if (end.isNotEmpty()) {
            val to = parseTime(end) ?: return "올바른 종료시각을 선택하세요."
            if (to < from) return "열차 출발 종료시각은 시작시각 이후여야 합니다."
        }
        if (adults !in 1..9) return "성인 인원은 1~9명으로 선택하세요."
        if (types.isEmpty()) return "열차 종류를 하나 이상 선택하세요."
        if (checkSelection && onlySelected && trainKeys.isEmpty()) return "좌석 현황에서 감시할 열차를 하나 이상 선택하세요."
        if (stopAt.isNotEmpty()) {
            val time = parseTime(stopAt) ?: return "감시 종료시각을 다시 선택하세요."
            if (checkPast && !time.isAfter(now.toLocalTime())) return "오늘의 감시 종료시각이 이미 지났습니다."
        }
        return null
    }

    fun summary(): String = "$departure → $arrival\n${displayDate(date)} · ${displayTime(start)}–${if (end.isBlank()) "이후 전체" else displayTime(end)}\n성인 ${adults}명 · ${seatLabel(seat)} · ${types.joinToString { it.label }}"

    fun includes(train: Train): Boolean = types.any { it.matches(train.trainTypeName) } &&
        train.depDate == date && train.depTime >= start && (end.isBlank() || train.depTime <= end)

    fun targets(train: Train): Boolean = includes(train) && (!onlySelected || key(train) in trainKeys)

    fun deadline(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? = when {
        stopAt.isNotBlank() -> now.toLocalDate().atTime(parseTime(stopAt))
        stopAfterMinutes > 0 -> now.plusMinutes(stopAfterMinutes.toLong())
        else -> null
    }

    fun json(): String = JSONObject().apply {
        put("departure", departure); put("arrival", arrival); put("date", date)
        put("start", start); put("end", end); put("adults", adults); put("seat", seat)
        put("types", JSONArray(types.map { it.name })); put("stopAfterMinutes", stopAfterMinutes)
        put("stopAt", stopAt); put("onlySelected", onlySelected); put("trainKeys", JSONArray(trainKeys.toList()))
    }.toString()

    companion object {
        val INTERVALS = listOf(1, 3, 5, 10, 15, 30, 60)
        fun parseTime(value: String): LocalTime? = runCatching {
            require(value.length == 6)
            LocalTime.parse(value, DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT))
        }.getOrNull()
        fun displayTime(value: String): String = if (value.length >= 4) "${value.take(2)}:${value.substring(2, 4)}" else value
        fun displayDate(value: String): String = runCatching {
            LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN))
        }.getOrDefault("날짜 선택")
        fun seatLabel(value: String): String = when (value) {
            KorailApi.SEAT_SPECIAL -> "특실"
            KorailApi.SEAT_ANY -> "일반+특실"
            else -> "일반실"
        }
        fun key(train: Train): String = "${train.depDate}:${train.trainTypeCode}:${train.trainNo}:${train.depTime}"
        fun fromJson(value: String): ReservationOptions? = runCatching {
            val j = JSONObject(value)
            val typeArray = j.getJSONArray("types")
            val keys = j.optJSONArray("trainKeys") ?: JSONArray()
            ReservationOptions(j.getString("departure"), j.getString("arrival"), j.getString("date"),
                j.getString("start"), j.optString("end"), j.getInt("adults"), j.getString("seat"),
                (0 until typeArray.length()).map { TrainType.valueOf(typeArray.getString(it)) }.toSet(),
                j.optInt("stopAfterMinutes"), j.optString("stopAt"), j.optBoolean("onlySelected"),
                (0 until keys.length()).map { keys.getString(it) }.toSet())
        }.getOrNull()
    }
}
