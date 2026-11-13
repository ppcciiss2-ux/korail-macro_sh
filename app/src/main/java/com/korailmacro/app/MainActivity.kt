package com.korailmacro.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.korail.ConnectionDiagnostics
import com.korailmacro.app.korail.Stations
import com.korailmacro.app.korail.Train
import com.korailmacro.app.korail.TrainType
import com.korailmacro.app.notify.TelegramNotifier
import com.korailmacro.app.update.UpdateActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Native Android views: one visible navigation model, one immutable run configuration. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var options: ReservationOptions
    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var startButton: MaterialButton
    private val tabs = linkedMapOf<String, MaterialButton>()
    private var currentPage = "home"
    private var busyStarting = false
    private var queryBusy = false
    private var queryError = ""
    private var queryTime = ""
    private var queriedOptions: ReservationOptions? = null
    private var trains = emptyList<Train>()
    private var stateTitle: TextView? = null
    private var stateDetail: TextView? = null
    private var logView: TextView? = null
    private var runStrip: TextView? = null
    private var saveNote: TextView? = null
    private var startError = ""
    private var logExpanded = false
    private var settingsDraft: SettingsDraft? = null
    private var settingsBusy = false
    private var settingsFeedback = ""
    private var loginFeedback = ""
    private var diagnosticFeedback = ""
    private var diagnosticBusy = false
    private var telegramFeedback = ""
    private var verifiedCredentials: Triple<String, String, String>? = null
    private val bg get() = color(R.color.bgColor)
    private val surface get() = color(R.color.cardColor)
    private val ink get() = color(R.color.textPrimary)
    private val muted get() = color(R.color.textSecondary)
    private val accent get() = color(R.color.colorAccent)
    private val running get() = ServiceBus.running.value || busyStarting

    private data class SettingsDraft(var type: String, var id: String, var password: String,
        var token: String, var chat: String, var interval: Int, var theme: Int, var alarm: Boolean)

    /** Back from any other tab returns to the conditions tab first instead of closing the app. */
    private val backToHome = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() { showPage("home") } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        options = prefs.draft
        currentPage = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        if (savedInstanceState != null && savedInstanceState.containsKey("settingsType")) {
            settingsDraft = SettingsDraft(savedInstanceState.getString("settingsType").orEmpty(),
                savedInstanceState.getString("settingsId").orEmpty(), savedInstanceState.getString("settingsPassword").orEmpty(),
                savedInstanceState.getString("settingsToken").orEmpty(), savedInstanceState.getString("settingsChat").orEmpty(),
                savedInstanceState.getInt("settingsInterval", 5), savedInstanceState.getInt("settingsTheme", ThemePrefs.mode(this)),
                savedInstanceState.getBoolean("settingsAlarm", true))
        }
        onBackPressedDispatcher.addCallback(this, backToHome)
        buildShell()
        showPage(currentPage)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ServiceBus.running.collect {
                    if (it) busyStarting = false
                    renderPage()
                } }
                launch { ServiceBus.status.collect { updateStatus() } }
                launch { ServiceBus.log.collect { logView?.text = it } }
                launch { ServiceBus.result.collect { value ->
                    // A new booking brings its result to the front once; later visits are the user's choice.
                    val booked = runCatching { JSONObject(value).optString("createdAt") }.getOrDefault("")
                    if (booked.isNotBlank() && booked != ServiceBus.seenResult) { ServiceBus.seenResult = booked; showPage("result") }
                    else if (currentPage == "result") renderPage()
                } }
                launch {
                    var lastDay = LocalDate.now()
                    while (true) {
                        delay(60_000L - System.currentTimeMillis() % 60_000L)
                        val today = LocalDate.now()
                        if (today != lastDay) refreshExpiredOptions()
                        lastDay = today
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read permissions too: the battery dialog returns here without restarting the activity.
        if (::prefs.isInitialized) { refreshExpiredOptions(); if (currentPage == "home") renderPage() }
    }

    private fun refreshExpiredOptions() {
        val updated = options.refreshed(LocalDateTime.now(), running)
        if (updated == options) return
        options = updated // refreshed() already reset the selection; this date·time correction keeps that behavior
        changeOptions(updated)
        toast("지난 예매 조건을 현재 날짜·시간으로 변경했습니다.")
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); showPage(intent.getStringExtra("page") ?: "home") }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", currentPage)
        settingsDraft?.let {
            outState.putString("settingsType", it.type); outState.putString("settingsId", it.id)
            outState.putString("settingsPassword", it.password); outState.putString("settingsToken", it.token)
            outState.putString("settingsChat", it.chat); outState.putInt("settingsInterval", it.interval)
            outState.putInt("settingsTheme", it.theme); outState.putBoolean("settingsAlarm", it.alarm)
        }
        super.onSaveInstanceState(outState)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rounded(fill: Int, radius: Int = 16) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun caption(value: String) = text(value, 12f).apply { setTextColor(muted) }
    private fun button(value: CharSequence, primary: Boolean = false, action: () -> Unit) = MaterialButton(this).apply {
        text = value; isAllCaps = false; textSize = 14f; cornerRadius = dp(10)
        minHeight = dp(48); minimumWidth = 0
        insetTop = dp(3); insetBottom = dp(3)
        // A disabled button must look disabled: one flat color made locked forms seem merely unresponsive.
        backgroundTintList = states(if (primary) accent else surface, if (primary) color(R.color.borderColor) else surface)
        setTextColor(states(if (primary) android.graphics.Color.WHITE else ink, ColorUtils.setAlphaComponent(muted, 0x99)))
        setOnClickListener { action() }
    }
    private fun states(enabled: Int, disabled: Int) = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, enabled))
    private fun LinearLayout.add(view: View) = addView(view, LinearLayout.LayoutParams(-1, -2))
    private fun panel(parent: LinearLayout = content, body: LinearLayout.() -> Unit): LinearLayout = column().apply {
        background = rounded(surface); setPadding(dp(16), dp(12), dp(16), dp(12))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        body()
    }
    private fun row(parent: LinearLayout, vararg views: View) {
        parent.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { index, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) }) }
        })
    }
    private fun divider(parent: LinearLayout) { parent.addView(View(this).apply { setBackgroundColor(color(R.color.borderColor)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) }) }

    /** Small label over a larger bold value, so the value is what the eye lands on. */
    private fun labeled(label: String, value: String): CharSequence = SpannableString("$label\n$value").apply {
        setSpan(RelativeSizeSpan(0.82f), 0, label.length, 0); setSpan(ForegroundColorSpan(muted), 0, label.length, 0)
        setSpan(RelativeSizeSpan(1.2f), label.length + 1, length, 0); setSpan(StyleSpan(Typeface.BOLD), label.length + 1, length, 0)
    }
    /** One row of small buttons that scrolls sideways. */
    private fun chips(labels: List<String>, action: (Int) -> Unit) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@MainActivity).apply {
            labels.forEachIndexed { index, label ->
                addView(button(label) { action(index) }.apply { textSize = 13f; strokeWidth = dp(1); strokeColor = ColorStateList.valueOf(color(R.color.borderColor)) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
        })
    }
    private fun confirm(message: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton(positive) { _, _ -> action() }.setNegativeButton("취소", null).show()
    }

    private fun buildShell() {
        val root = column().apply { setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(2)) }
        header.addView(text("코레일 예매 매크로", 18f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(caption("v${BuildConfig.VERSION_NAME}"))
        root.add(header)
        content = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = column().apply { setPadding(dp(16), dp(8), dp(16), dp(4)); setBackgroundColor(bg) }
        startButton = button("자동 예약 시작", true) { toggleRun() }
        root.add(footer)
        // Tabs sit at the bottom, within thumb reach, under the action of the current tab.
        val nav = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(6)) }
        listOf("home" to "예매 조건", "seats" to "좌석 현황", "result" to "예약 결과", "settings" to "설정").forEach { (key, label) ->
            val b = button(label) { showPage(key) }.apply { textSize = 13f; setPadding(dp(2), 0, dp(2), 0) }
            tabs[key] = b
            nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.add(nav)
        setContentView(root)
    }

    /** Fixed bottom bar: start/stop wherever trains are chosen, save on settings. */
    private fun renderFooter() {
        footer.removeAllViews()
        footer.visibility = if (currentPage == "result") View.GONE else View.VISIBLE
        if (currentPage == "settings") {
            saveNote = caption("").apply { gravity = Gravity.CENTER }; footer.add(saveNote!!)
            footer.add(button("설정 저장", true) { saveSettings() }.apply { isEnabled = !settingsBusy })
        } else if (currentPage != "result") {
            // What a run will book, right above the button that starts it.
            footer.add(if (startError.isNotBlank()) text(startError, 13f).apply {
                setTextColor(color(R.color.colorError)); gravity = Gravity.CENTER
                val target = if ("설정" in startError) "settings" else if ("좌석 현황" in startError) "seats" else null
                if (target != null && target != currentPage) setOnClickListener { showPage(target) }
            } else caption(if (running) "실행 중 · ${activeOptions().brief()}" else "${options.brief()}\n빈 좌석 발견 시 자동 예약 · 결제는 직접 진행").apply { gravity = Gravity.CENTER })
            startButton.text = if (running) "자동 예약 중지" else "자동 예약 시작"
            startButton.backgroundTintList = states(if (running) color(R.color.colorStop) else accent, color(R.color.borderColor))
            startButton.isEnabled = !busyStarting && ServiceBus.status.value.title != "중지 처리 중"
            footer.add(startButton)
        }
        updateSaveNote() // also keeps the unsaved mark on the settings tab while another tab is open
    }

    private fun settingsDirty(): Boolean {
        val d = settingsDraft ?: return false
        return d.type != prefs.loginType || d.id.trim() != prefs.loginId || d.password != prefs.password ||
            d.token.trim() != prefs.telegramToken || d.chat.trim() != prefs.telegramChatId ||
            d.interval != prefs.pollIntervalSec || d.theme != ThemePrefs.mode(this) || d.alarm != prefs.successAlarm
    }
    private fun updateSaveNote() {
        val dirty = settingsDirty()
        tabs["settings"]?.text = if (dirty) "설정 •" else "설정"
        saveNote?.apply {
            text = if (dirty) "저장하지 않은 변경이 있습니다." else settingsFeedback.ifBlank {
                if (running) "실행 중 설정 변경은 다음 시작부터 적용됩니다." else "변경한 뒤 저장을 눌러 적용하세요." }
            setTextColor(if (dirty) color(R.color.colorError) else muted)
        }
    }

    private fun showPage(page: String) {
        val leavingUnsaved = currentPage == "settings" && page != "settings" && settingsDirty()
        currentPage = if (page in tabs) page else "home"
        startError = ""
        if (leavingUnsaved) toast("저장하지 않은 설정 변경이 있습니다. 설정 탭에서 저장하세요.")
        renderPage(); scroll.scrollTo(0, 0)
        if (currentPage == "seats" && queriedOptions == null && !queryBusy) runQuery()
    }
    private fun renderPage() {
        if (!::content.isInitialized) return
        stateTitle = null; stateDetail = null; logView = null; runStrip = null; saveNote = null
        backToHome.isEnabled = currentPage != "home"
        content.removeAllViews()
        tabs.forEach { (key, b) ->
            b.isSelected = key == currentPage
            b.setTextColor(if (key == currentPage) accent else muted)
            b.strokeWidth = if (key == currentPage) dp(1) else 0
            b.strokeColor = ColorStateList.valueOf(accent)
        }
        if (running && currentPage != "home") {
            // The status card lives on the conditions tab; keep an active run visible everywhere else.
            runStrip = text("", 14f, true).apply {
                setTextColor(accent); background = rounded(surface, 10); setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { showPage("home") }
            }
            content.addView(runStrip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            updateStatus()
        }
        renderFooter()
        when (currentPage) { "home" -> renderHome(); "seats" -> renderSeats(); "result" -> renderResult(); "settings" -> renderSettings() }
    }

    private fun changeOptions(value: ReservationOptions, keepSelection: Boolean = false) {
        val next = if (keepSelection) value else value.carrySelection(options)
        val dropped = if (keepSelection) 0 else options.trainKeys.size - next.trainKeys.size
        options = next
        prefs.draft = options
        if (!keepSelection) { queriedOptions = null; trains = emptyList(); queryTime = ""; queryError = "" }
        startError = ""
        renderPage()
        if (dropped > 0) toast("조건이 바뀌어 선택한 열차 ${dropped}개가 해제됐습니다. 좌석 현황에서 다시 선택하세요.")
    }
    private fun activeOptions() = if (ServiceBus.running.value) ServiceBus.status.value.options ?: options else options
    private fun renderHome() {
        panel {
            stateTitle = text("", 16f, true).apply { setTextColor(accent) }; add(stateTitle!!)
            stateDetail = caption(""); add(stateDetail!!)
        }
        updateStatus()
        renderReady()
        if (running) {
            // A run cannot be edited: show what it is doing instead of a locked form.
            content.add(caption("실행 중에는 조건을 바꿀 수 없습니다. 바꾸려면 먼저 중지하세요.").apply { setPadding(dp(4), 0, dp(4), dp(8)) })
            renderLog(true)
            return
        }
        // Show a problem next to the field it belongs to, before the user presses start.
        val issue = options.issue(checkSelection = false)
        fun LinearLayout.problem(field: ReservationOptions.Field) {
            if (issue?.field == field) add(text(issue.message, 13f).apply { setTextColor(color(R.color.colorError)) })
        }
        panel {
            val recent = prefs.recentRoutes().filterNot { it.first == options.departure && it.second == options.arrival }
            if (recent.isNotEmpty()) {
                add(caption("최근 구간"))
                add(chips(recent.map { "${it.first} → ${it.second}" }) { changeOptions(options.copy(departure = recent[it].first, arrival = recent[it].second)) })
            }
            // Saved conditions load with one tap; the 즐겨찾기 button below still lists them for review and deletion.
            val favorites = prefs.favorites()
            if (favorites.isNotEmpty()) {
                add(caption("저장한 조건 바로 불러오기"))
                add(chips(favorites.map { "★ ${it.first}" }) { applyFavorite(favorites[it].first, favorites[it].second) })
            }
            row(this, button(labeled("출발역", options.departure.ifBlank { "선택" })) { stationPicker(true) },
                button(labeled("도착역", options.arrival.ifBlank { "선택" })) { stationPicker(false) })
            add(button("⇄ 출발·도착 교환") { changeOptions(options.copy(departure = options.arrival, arrival = options.departure)) })
            problem(ReservationOptions.Field.STATIONS)
            divider(this)
            add(button(labeled("가는 날", ReservationOptions.displayDate(options.date))) { datePicker() })
            val today = LocalDate.now()
            row(this, *listOf("오늘", "내일", "모레").mapIndexed { offset, label ->
                val day = today.plusDays(offset.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE)
                button(label, day == options.date) { changeOptions(options.copy(date = day)) }
            }.toTypedArray())
            row(this,
                button(labeled("출발시간 이후", ReservationOptions.displayTime(options.start))) {
                    hourPicker("출발시간 이후", options.start, false) { changeOptions(options.copy(start = it)) }
                },
                button(labeled("출발시간 까지", if (options.end.isBlank()) "선택 안함" else ReservationOptions.displayTime(options.end))) {
                    hourPicker("출발시간 까지", options.end, true) { changeOptions(options.copy(end = it)) }
                })
            add(caption("‘까지’를 비우면 시작시각 이후 전체 열차를 조회합니다."))
            problem(ReservationOptions.Field.TIME)
            row(this, button("☆ 조건 저장") { saveFavorite() }, button("즐겨찾기") { loadFavorite() })
        }
        panel {
            add(text("탑승 조건", 16f, true))
            row(this, button("성인 ${options.adults}명") {
                choose("성인 인원", (1..9).map { "${it}명" }, options.adults - 1) { changeOptions(options.copy(adults = it + 1)) }
            }, button(ReservationOptions.seatLabel(options.seat)) {
                val seats = listOf(KorailApi.SEAT_GENERAL, KorailApi.SEAT_SPECIAL, KorailApi.SEAT_ANY)
                choose("좌석 등급", seats.map(ReservationOptions::seatLabel), seats.indexOf(options.seat)) { changeOptions(options.copy(seat = seats[it])) }
            })
            if (options.seat == KorailApi.SEAT_ANY) add(caption("한 열차에 두 등급 모두 있으면 일반실을 먼저 예약합니다."))
            TrainType.entries.chunked(2).forEach { types -> row(this, *types.map { type ->
                CheckBox(this@MainActivity).apply {
                    text = type.label; isChecked = type in options.types; minHeight = dp(48)
                    setOnCheckedChangeListener { _, checked -> changeOptions(options.copy(types = if (checked) options.types + type else options.types - type)) }
                }
            }.toTypedArray()) }
            problem(ReservationOptions.Field.TYPES)
            divider(this)
            add(button(labeled("자동 예약 종료 시점 · 열차 출발시간과 별개", options.stopLabel())) {
                choose("자동 예약 종료 시점", listOf("직접 중지할 때까지", "30분 후", "1시간 후", "시각 지정 (지난 시각은 내일)")) {
                    when (it) {
                        0 -> changeOptions(options.copy(stopAfterMinutes = 0, stopAt = ""), true)
                        1 -> changeOptions(options.copy(stopAfterMinutes = 30, stopAt = ""), true)
                        2 -> changeOptions(options.copy(stopAfterMinutes = 60, stopAt = ""), true)
                        else -> timePicker("230000") { time -> changeOptions(options.copy(stopAt = time, stopAfterMinutes = 0), true) }
                    }
                }
            })
            problem(ReservationOptions.Field.STOP)
            if (options.onlySelected && options.trainKeys.isEmpty()) {
                add(text("예약 대상: 선택한 열차 없음 · 이대로는 시작할 수 없습니다.", 13f).apply { setTextColor(color(R.color.colorError)) })
                row(this, button("좌석 현황에서 선택") { showPage("seats") },
                    button("전체 열차로 변경") { changeOptions(options.copy(onlySelected = false), true) })
            } else add(caption(if (options.onlySelected) "예약 대상: 선택한 열차 ${options.trainKeys.size}개" else "예약 대상: 조건에 맞는 전체 열차"))
        }
        renderLog(false)
    }
    private fun renderLog(always: Boolean) {
        if (!always) content.add(button(if (logExpanded) "실행 로그 접기" else "실행 로그 펼치기") { logExpanded = !logExpanded; renderPage() })
        if (!always && !logExpanded) return
        content.add(button("크게 보기") { showFullLog() })
        logView = caption(ServiceBus.log.value).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        content.add(logView!!)
    }
    private fun updateStatus() {
        val s = ServiceBus.status.value
        if (busyStarting && s.title == "시작할 수 없음") { busyStarting = false; renderPage(); return }
        val idle = s.title == "시작 대기" // a fresh process: show how the previous run ended
        val title = if (idle) prefs.lastRunStatus else s.title
        stateTitle?.text = title
        stateTitle?.setTextColor(when {
            title == "예약 성공" -> color(R.color.colorSuccess)
            "오류" in title || "실패" in title || title == "시작할 수 없음" -> color(R.color.colorError)
            running -> accent
            else -> ink
        })
        stateDetail?.text = if (running) "${s.detail}\n적용 조건: ${activeOptions().summary()}"
            else listOf(if (idle) prefs.lastRunDetail else s.detail, "조회 간격 ${prefs.pollIntervalSec}초").filter(String::isNotBlank).joinToString("\n")
        val strip = "● 자동 예약 실행 중 · ${s.title}\n눌러서 상태 보기"
        runStrip?.text = strip
        if (::startButton.isInitialized) startButton.isEnabled = !busyStarting && s.title != "중지 처리 중"
    }
    /** Only what is still missing, each as a button that goes straight to the fix. Gone once all are done. */
    private fun renderReady() {
        val todo = listOfNotNull<Pair<String, () -> Unit>>(
            if (prefs.loginVerified) null else "코레일 로그인 확인" to { showPage("settings") },
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) null else "알림 허용" to { notificationSettings() },
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) null else "배터리 최적화 제외" to { batterySettings() })
        if (todo.isEmpty()) return
        panel {
            add(text("시작 전 준비 ${todo.size}가지", 16f, true))
            add(caption("눌러서 바로 설정하세요. 모두 끝나면 이 칸은 사라집니다."))
            todo.forEach { (label, action) -> add(button("$label  ›") { action() }) }
        }
    }
    private fun toggleRun() {
        if (running) { startService(Intent(this, ReservationService::class.java).setAction(ReservationService.ACTION_STOP)); return }
        if (diagnosticBusy) { toast("연결 진단이 끝난 후 시작하세요."); return }
        val error = options.error() ?: if (prefs.loginId.isBlank() || prefs.password.isBlank()) "설정에서 로그인 정보를 입력하세요." else null
        if (error != null) { startError = error; renderPage(); return }
        prefs.draft = options; prefs.pushRecentRoute(options.departure, options.arrival)
        startError = ""; ServiceBus.clear(); busyStarting = true; renderPage()
        try { ContextCompat.startForegroundService(this, Intent(this, ReservationService::class.java).putExtra(ReservationService.EXTRA_OPTIONS, options.json())) }
        catch (e: Exception) { busyStarting = false; ServiceBus.update("시작 실패", e.message.orEmpty()); renderPage() }
    }

    private fun choose(title: String, values: List<String>, selected: Int = -1, action: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(), selected) { d, which -> d.dismiss(); action(which) }.setNegativeButton("닫기", null).show()
    }
    private fun datePicker() {
        val day = runCatching { LocalDate.parse(options.date, DateTimeFormatter.BASIC_ISO_DATE) }.getOrDefault(LocalDate.now())
        DatePickerDialog(this, { _, y, m, d -> changeOptions(options.copy(date = LocalDate.of(y, m + 1, d).format(DateTimeFormatter.BASIC_ISO_DATE))) }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }
    private fun timePicker(value: String, clearable: Boolean = false, action: (String) -> Unit) {
        val time = ReservationOptions.parseTime(value) ?: LocalTime.now().withSecond(0)
        val dialog = TimePickerDialog(this, { _, hour, minute -> action("%02d%02d00".format(hour, minute)) }, time.hour, time.minute, true)
        if (clearable) dialog.setButton(TimePickerDialog.BUTTON_NEUTRAL, "선택 안함") { _, _ -> action("") }
        dialog.show()
    }
    /** Whole hours cover almost every search; exact minutes stay one tap further. */
    private fun hourPicker(title: String, value: String, clearable: Boolean, action: (String) -> Unit) {
        val hours = (0..23).map { "%02d0000".format(it) }
        val offset = if (clearable) 1 else 0
        val labels = (if (clearable) listOf("선택 안함") else emptyList()) + hours.map(ReservationOptions::displayTime) + "분 단위로 지정…"
        val selected = if (clearable && value.isBlank()) 0 else hours.indexOf(value).let { if (it < 0) -1 else it + offset }
        choose(title, labels, selected) { index ->
            when {
                clearable && index == 0 -> action("")
                index == labels.lastIndex -> timePicker(value, false, action)
                else -> action(hours[index - offset])
            }
        }
    }
    private val majorStations = listOf("서울", "용산", "광명", "수원", "천안아산", "오송", "대전", "동대구", "신경주", "울산", "부산",
        "익산", "광주송정", "목포", "전주", "순천", "강릉", "포항", "마산", "청량리")
    private fun stationPicker(departure: Boolean) {
        val other = if (departure) options.arrival else options.departure
        val current = if (departure) options.departure else options.arrival
        // The other end of the trip cannot be picked again, so it is not offered.
        val names = Stations.NAMES.filter { it != other }
        var shown: AlertDialog? = null
        fun pick(station: String) {
            shown?.dismiss()
            changeOptions(if (departure) options.copy(departure = station) else options.copy(arrival = station))
            // A fresh trip needs both ends: continue straight to the arrival station.
            if (departure && options.arrival.isBlank()) stationPicker(false)
        }
        val root = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val search = EditText(this).apply { hint = "역 이름 또는 초성 검색"; setSingleLine() }
        root.add(search)
        val quick = (prefs.recentRoutes().flatMap { listOf(it.first, it.second) } + majorStations).distinct().filter { it in names }
        root.add(caption("최근·주요 역"))
        root.add(chips(quick.map { if (it == current) "✓ $it" else it }) { pick(quick[it]) })
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names.toMutableList())
        val list = ListView(this).apply { this.adapter = adapter }
        root.add(caption("초성으로 이동"))
        root.add(chips(Stations.AVAILABLE_INITIALS.map { it.toString() }) { index ->
            search.setText("")
            val initial = Stations.AVAILABLE_INITIALS[index].toString()
            names.indexOfFirst { Stations.matches(it.take(1), initial) }.takeIf { it >= 0 }?.let(list::setSelection)
        })
        root.addView(list, LinearLayout.LayoutParams(-1, dp(240)))
        val dialog = AlertDialog.Builder(this).setTitle((if (departure) "출발역 선택" else "도착역 선택") + if (current.isBlank()) "" else " · 현재 $current")
            .setView(root).setNegativeButton("닫기", null).create()
        shown = dialog
        list.setOnItemClickListener { _, _, position, _ -> pick(adapter.getItem(position).orEmpty()) }
        search.addTextChangedListener(watcher { query -> adapter.clear(); adapter.addAll(names.filter { Stations.matches(it, query) }) })
        dialog.show()
    }
    private fun watcher(action: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { action(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: Editable?) {}
    }
    private fun saveFavorite() {
        val name = EditText(this).apply { hint = "예: 퇴근 KTX"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("조건 즐겨찾기 저장").setView(name).setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val title = name.text.toString().trim()
                val saved = prefs.favorites()
                fun save() { prefs.saveFavorite(title, options); renderPage(); toast("조건을 저장했습니다.") }
                // Nothing is replaced or dropped without asking.
                when {
                    title.isBlank() -> toast("이름을 입력해야 저장됩니다.")
                    saved.any { it.first == title } -> confirm("‘$title’ 즐겨찾기가 이미 있습니다. 지금 조건으로 덮어쓸까요?", "덮어쓰기") { save() }
                    saved.size >= 10 -> confirm("즐겨찾기는 10개까지입니다. 가장 오래된 ‘${saved.last().first}’을(를) 지우고 저장할까요?", "지우고 저장") { save() }
                    else -> save()
                }
            }.show()
    }
    private fun loadFavorite() {
        val favorites = prefs.favorites()
        if (favorites.isEmpty()) { toast("저장된 조건이 없습니다."); return }
        choose("즐겨찾기", favorites.map { it.first }) { i ->
            val (name, value) = favorites[i]
            AlertDialog.Builder(this).setTitle(name).setMessage(value.summary()).setPositiveButton("불러오기") { _, _ -> applyFavorite(name, value)
            }.setNeutralButton("삭제") { _, _ ->
                confirm("‘$name’ 즐겨찾기를 지울까요?", "지우기") { prefs.removeFavorite(name); renderPage(); toast("즐겨찾기를 지웠습니다.") }
            }.setNegativeButton("닫기", null).show()
        }
    }
    /** The saved date is usually in the past, so a favorite keeps the date currently on screen. */
    private fun applyFavorite(name: String, value: ReservationOptions) {
        changeOptions(value.copy(date = options.date, onlySelected = false, trainKeys = emptySet()))
        toast("‘$name’ 조건을 불러왔습니다. 가는 날을 확인하세요.")
    }

    @Volatile private var queryApi: KorailApi? = null
    @Volatile private var queryAccount: Triple<String, String, String>? = null
    private var onlyAvailable = false

    private fun runQuery() {
        if (diagnosticBusy) { toast("연결 진단이 끝난 후 조회하세요."); return }
        if (queryBusy) return
        val config = activeOptions()
        val error = config.error(checkSelection = false, checkPast = !running) ?: if (prefs.loginId.isBlank() || prefs.password.isBlank()) "설정에서 로그인 정보를 입력하세요." else null
        if (error != null) { queryError = error; if (currentPage == "seats") renderPage(); return }
        val account = Triple(prefs.loginType, prefs.loginId, prefs.password)
        queryBusy = true; queryError = ""; queriedOptions = config; trains = emptyList(); queryTime = ""
        if (currentPage == "seats") renderPage()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fun search(api: KorailApi) = api.searchTrain(config.departure, config.arrival, config.date, config.start, config.adults, config.end).filter(config::includes)
                    // Reuse the logged-in session: logging in again for every refresh made each query slow.
                    // Any failure on the reused session (it may have expired) falls back to a fresh login.
                    val reused = queryApi.takeIf { queryAccount == account }?.let { api -> try { search(api) } catch (e: Exception) { null } }
                    reused ?: KorailApi.forDevice(this@MainActivity).let { api ->
                        api.login(account.first, account.second, account.third)
                        queryApi = api; queryAccount = account
                        search(api)
                    }
                }
                // Ignore a response if the user edited the form while this independent request was running.
                if (queriedOptions == config) {
                    trains = result; queryTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    prefs.pushRecentRoute(config.departure, config.arrival)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (queriedOptions == config) queryError = "조회 실패: ${e.message}" }
            finally { queryBusy = false; if (currentPage == "seats") renderPage() }
        }
    }
    private fun renderSeats() {
        val active = activeOptions()
        content.add(text(if (running) "실행 중인 조건으로 조회" else "현재 조건으로 조회", 17f, true))
        (queriedOptions ?: active).let { if (it.departure.isNotBlank() && it.arrival.isNotBlank()) content.add(caption(it.summary())) }
        row(content, button(if (queryBusy) "조회 중…" else "다시 조회") { runQuery() }.apply { isEnabled = !queryBusy },
            CheckBox(this).apply {
                text = "예약 가능만 보기"; isChecked = onlyAvailable; minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> onlyAvailable = checked; renderPage() }
            })
        if (queryError.isNotBlank() && queryError != startError) content.add(text(queryError).apply { setTextColor(color(R.color.colorError)) })
        val shown = if (onlyAvailable) trains.filter { it.hasGeneralSeat || it.hasSpecialSeat } else trains
        if (queryTime.isNotBlank()) content.add(caption("마지막 조회 $queryTime · ${trains.size}개 열차" + if (onlyAvailable) " 중 예약 가능 ${shown.size}개" else ""))
        if (!queryBusy && queryError.isBlank() && shown.isEmpty()) content.add(text(if (trains.isEmpty()) "조건에 맞는 열차가 없습니다." else "지금 예약 가능한 열차가 없습니다."))
        // Tapping a train is the selection; nothing selected means every matching train.
        when {
            running -> content.add(caption((if (active.onlySelected) "선택한 열차 ${active.trainKeys.size}개만 예약 중" else "조건에 맞는 전체 열차 예약 중") +
                "\n실행 중에는 예약할 열차를 바꿀 수 없습니다."))
            options.onlySelected && options.trainKeys.isEmpty() -> {
                content.add(text("조건이 바뀌어 열차 선택이 해제됐습니다. 예약할 열차를 다시 누르세요.", 13f).apply { setTextColor(color(R.color.colorError)) })
                content.add(button("전체 열차로 변경") { changeOptions(options.copy(onlySelected = false), true) })
            }
            options.onlySelected -> row(content, text("선택한 열차 ${options.trainKeys.size}개만 예약", 14f, true).apply { setTextColor(accent) },
                button("선택 해제") { changeOptions(options.copy(onlySelected = false, trainKeys = emptySet()), true) })
            else -> content.add(caption("예약 대상: 조건에 맞는 전체 열차 · 열차를 누르면 그 열차만 예약합니다."))
        }
        shown.forEach { train ->
            val key = ReservationOptions.key(train)
            val selected = key in active.trainKeys
            val card = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(8), dp(10))
                background = rounded(surface, 12).apply { if (selected) setStroke(dp(2), accent) }
                contentDescription = "${train.trainTypeName} ${train.trainNo} ${if (selected) "선택됨" else "선택 안 됨"}"
                if (!running) setOnClickListener { changeOptions(options.toggleTrain(key), true) }
            }
            val times = column()
            times.add(text("${ReservationOptions.displayTime(train.depTime)} → ${ReservationOptions.displayTime(train.arrTime)}", 17f, true))
            times.add(caption("${train.trainTypeName} ${train.trainNo}"))
            card.addView(times, LinearLayout.LayoutParams(0, -2, 1f))
            fun seat(label: String, available: Boolean) = text("$label ${if (available) "가능" else "매진"}", 13f).apply {
                setTextColor(if (available) color(R.color.colorSuccess) else muted); gravity = Gravity.END
            }
            val seats = column()
            seats.add(seat("일반실", train.hasGeneralSeat)); seats.add(seat("특실", train.hasSpecialSeat))
            card.addView(seats, LinearLayout.LayoutParams(-2, -2))
            card.addView(text(if (selected) "✓" else "", 18f, true).apply { setTextColor(accent); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), -2))
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderResult() {
        val result = runCatching { JSONObject(ServiceBus.result.value.ifBlank { prefs.lastResult }) }.getOrNull()
        if (result == null) { panel { add(text("아직 예약 결과가 없습니다.", 17f, true)); add(caption("자동 예약에 성공하면 마지막 결과를 여기에 보관합니다.")) }; return }
        // A record whose train has already left must not look like a booking waiting for payment.
        val departs = runCatching {
            LocalDate.parse(result.optString("date"), DateTimeFormatter.BASIC_ISO_DATE).atTime(ReservationOptions.parseTime(result.optString("time"))!!)
        }.getOrNull()
        val past = departs != null && departs.isBefore(LocalDateTime.now())
        panel {
            if (past) {
                add(text("지난 예약 기록", 20f, true).apply { setTextColor(muted) })
                add(caption("이미 출발 시각이 지난 열차입니다."))
            } else add(text("✓ 예약이 완료됐어요", 22f, true).apply { setTextColor(color(R.color.colorSuccess)) })
            add(caption("마지막 예약 결과 · ${result.optString("createdAt").replace('T', ' ').take(19)}"))
            divider(this)
            listOf("구간" to result.optString("route"), "출발" to "${ReservationOptions.displayDate(result.optString("date"))} ${ReservationOptions.displayTime(result.optString("time"))}",
                "열차 / 좌석" to "${result.optString("train")} / ${result.optString("seat")}", "인원" to "성인 ${result.optInt("adults")}명", "예약번호" to result.optString("pnr")).forEach { (label, value) ->
                add(caption(label)); add(text(value, 16f, true))
            }
            add(button("예약번호 복사") { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("예약번호", result.optString("pnr"))); toast("예약번호를 복사했습니다.") })
            if (!past) {
                divider(this); add(text("직접 결제가 필요해요", 17f, true))
                add(text("코레일 앱에서 예약 내역과 정확한 결제 기한을 확인하세요."))
                add(button("코레일톡 열기", true) {
                    val talk = packageManager.getLaunchIntentForPackage("com.korail.talk")
                    runCatching { startActivity(talk ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.korail.talk"))) }
                        .onFailure { toast("코레일톡을 열 수 없습니다. 직접 실행해 결제하세요.") }
                })
            }
            add(caption("텔레그램: ${result.optString("telegram")}"))
            add(caption("이 결과는 예약 성공 당시 기록입니다. 이후 결제·취소 상태는 코레일에서 확인하세요."))
            add(button("기록 지우기") {
                confirm("이 예약 기록을 화면에서 지울까요? 코레일의 실제 예약은 바뀌지 않습니다.", "지우기") {
                    runCatching { prefs.lastResult = "" }; ServiceBus.result.value = ""; renderPage()
                }
            })
        }
    }

    /** Order: what everyone must set, then alerts, pace, look and permissions, and troubleshooting last. */
    private fun renderSettings() {
        val draft = settingsDraft ?: SettingsDraft(prefs.loginType, prefs.loginId, prefs.password, prefs.telegramToken, prefs.telegramChatId,
            prefs.pollIntervalSec, ThemePrefs.mode(this), prefs.successAlarm).also { settingsDraft = it }
        panel {
            add(text("코레일 로그인", 17f, true))
            val types = listOf(KorailApi.LOGIN_TYPE_EMAIL, KorailApi.LOGIN_TYPE_MEMBERSHIP, KorailApi.LOGIN_TYPE_PHONE)
            add(button(listOf("이메일", "회원번호", "휴대전화")[types.indexOf(draft.type).coerceAtLeast(0)]) {
                choose("로그인 방식", listOf("이메일", "회원번호", "휴대전화"), types.indexOf(draft.type)) { draft.type = types[it]; loginFeedback = ""; renderPage() }
            })
            field(this, "아이디", draft.id, if (draft.type == KorailApi.LOGIN_TYPE_EMAIL) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS else InputType.TYPE_CLASS_PHONE) { draft.id = it; loginFeedback = "" }
            field(this, "비밀번호", draft.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, secret = true) { draft.password = it; loginFeedback = "" }
            add(button(if (settingsBusy) "확인 중…" else "로그인 확인") { testLogin(draft) }.apply { isEnabled = !settingsBusy && !running })
            add(caption(loginFeedback.ifBlank { if (running) "실행 중에는 로그인 확인을 할 수 없습니다." else "확인에 성공하면 계정이 바로 저장됩니다." }))
        }
        panel {
            add(text("알림 연결", 17f, true))
            field(this, "텔레그램 봇 토큰", draft.token, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, secret = true) { draft.token = it }
            field(this, "Chat ID", draft.chat, InputType.TYPE_CLASS_TEXT) { draft.chat = it }
            add(caption("토큰: 텔레그램 @BotFather 에서 /newbot 으로 발급합니다.\nChat ID: 만든 봇에게 메시지를 한 번 보낸 뒤, 브라우저에서 api.telegram.org/bot<토큰>/getUpdates 를 열어 chat 의 id 값을 넣습니다."))
            add(button("테스트 알림 보내기") { testTelegram(draft) }.apply { isEnabled = !settingsBusy })
            add(caption(telegramFeedback.ifBlank { "예약 전에 알림 연결을 확인할 수 있습니다." }))
            divider(this)
            add(CheckBox(this@MainActivity).apply {
                text = "예약 성공 시 확인할 때까지 알림음 반복"; isChecked = draft.alarm; minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> draft.alarm = checked; updateSaveNote() }
            })
            add(caption("결제 기한이 짧아 놓치지 않도록 알림을 열 때까지 소리가 반복됩니다."))
        }
        panel {
            add(text("조회 간격", 17f, true))
            ReservationOptions.INTERVALS.chunked(4).forEach { group -> row(this, *group.map { seconds ->
                button("${seconds}초", seconds == draft.interval) { draft.interval = seconds; renderPage() }
            }.toTypedArray()) }
            add(caption("조회가 끝난 뒤 다음 조회까지의 대기시간입니다. 1·3초는 요청 빈도가 높으므로 기본값은 5초입니다."))
        }
        panel {
            add(text("화면과 권한", 17f, true))
            val modes = listOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
            val names = listOf("시스템 설정", "라이트", "다크")
            add(button("화면 테마 · ${names[modes.indexOf(draft.theme).coerceAtLeast(0)]}") {
                choose("화면 테마", names, modes.indexOf(draft.theme)) { draft.theme = modes[it]; renderPage() }
            })
            add(caption("테마는 설정 저장 시 적용됩니다. 작성 중인 입력은 유지됩니다."))
            add(button("알림 권한 설정") { notificationSettings() })
            add(button("배터리 최적화 설정") { batterySettings() })
        }
        panel {
            add(text("문제 해결", 17f, true))
            add(caption("연결 진단: 입력한 계정과 예매 조건으로 초기 통신 → 로그인 → 열차 조회 1회를 검사합니다."))
            add(button(if (settingsBusy) "처리 중…" else "연결 진단 실행") { runConnectionDiagnostics(draft) }
                .apply { isEnabled = !settingsBusy && !running && !queryBusy })
            add(caption(diagnosticFeedback.ifBlank { if (running) "실행 중에는 진단할 수 없습니다." else "버튼을 누를 때만 검사합니다. 예약은 실행하지 않습니다." }))
            add(button("진단 결과 복사") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("연결 진단", diagnosticFeedback))
                toast("진단 결과를 복사했습니다.")
            }.apply { isEnabled = diagnosticFeedback.isNotBlank() && !settingsBusy })
            divider(this)
            add(button("업데이트") { startActivity(Intent(this@MainActivity, UpdateActivity::class.java)) })
            add(caption("현재 버전 ${BuildConfig.VERSION_NAME} · 누를 때만 새 버전을 확인합니다."))
        }
    }
    private fun saveSettings() {
        val draft = settingsDraft ?: return
        val credentials = Triple(draft.type, draft.id.trim(), draft.password)
        val unchanged = credentials == Triple(prefs.loginType, prefs.loginId, prefs.password)
        prefs.loginVerified = verifiedCredentials == credentials || (unchanged && prefs.loginVerified)
        prefs.loginType = credentials.first; prefs.loginId = credentials.second; prefs.password = credentials.third
        prefs.telegramToken = draft.token.trim(); prefs.telegramChatId = draft.chat.trim(); prefs.pollIntervalSec = draft.interval
        prefs.successAlarm = draft.alarm
        ThemePrefs.setMode(this, draft.theme)
        settingsFeedback = if (running) "저장 완료 · 실행 중인 조회에는 적용되지 않으며 다음 시작부터 적용됩니다." else "설정을 저장했습니다."
        renderPage()
        AppCompatDelegate.setDefaultNightMode(draft.theme)
    }
    private fun field(parent: LinearLayout, label: String, value: String, type: Int, secret: Boolean = false, update: (String) -> Unit) {
        parent.add(caption(label))
        val edit = EditText(this).apply {
            contentDescription = label; inputType = type; setSingleLine(); textSize = 16f; minHeight = dp(48)
            setText(value); addTextChangedListener(watcher { update(it); updateSaveNote() })
        }
        parent.add(edit)
        // A pasted password or token can be checked before saving.
        if (secret) parent.add(CheckBox(this).apply {
            val showLabel = "$label 보기"
            text = showLabel; minHeight = dp(40)
            setOnCheckedChangeListener { _, shown ->
                edit.inputType = if (shown) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else type
                edit.setSelection(edit.text.length)
            }
        })
    }
    private fun testLogin(draft: SettingsDraft) {
        val credentials = Triple(draft.type, draft.id.trim(), draft.password)
        if (credentials.second.isBlank() || credentials.third.isBlank()) { loginFeedback = "아이디와 비밀번호를 입력하세요."; renderPage(); return }
        settingsBusy = true; loginFeedback = "로그인 확인 중…"; renderPage()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { KorailApi.forDevice(this@MainActivity).login(credentials.first, credentials.second, credentials.third) }
                verifiedCredentials = credentials
                // Save the account that was just verified: checking without saving left users unable to start.
                prefs.loginType = credentials.first; prefs.loginId = credentials.second; prefs.password = credentials.third
                prefs.loginVerified = true
                loginFeedback = "로그인 확인 성공 · 계정을 저장했습니다."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { loginFeedback = "로그인 실패: ${e.message}" }
            finally { settingsBusy = false; if (currentPage == "settings") renderPage() }
        }
    }
    private fun runConnectionDiagnostics(draft: SettingsDraft) {
        if (running || settingsBusy || queryBusy) return
        val config = options
        val error = config.error(checkSelection = false) ?: if (draft.id.isBlank() || draft.password.isBlank()) "아이디와 비밀번호를 입력하세요." else null
        if (error != null) { diagnosticFeedback = error; renderPage(); return }
        val credentials = Triple(draft.type, draft.id.trim(), draft.password)
        val metrics = resources.displayMetrics
        val environment = "앱 ${BuildConfig.VERSION_NAME}\n실제 Android SDK ${Build.VERSION.SDK_INT} · 화면 ${metrics.widthPixels}×${metrics.heightPixels}px\n" +
            "전송 Android SDK ${Build.VERSION.SDK_INT} · 화면 크기 전송 생략\n${KorailApi.TRANSPORT_DESCRIPTION}\n기기 정보: 실제 휴대폰 값 사용 (식별자는 결과에 제외)\n"
        diagnosticBusy = true; settingsBusy = true; diagnosticFeedback = "진단 중…"; renderPage()
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val context = coroutineContext
                    ConnectionDiagnostics.run(credentials.first, credentials.second, credentials.third,
                        config.departure, config.arrival, config.date, config.start, config.adults,
                        checkActive = { context.ensureActive() },
                        factory = { KorailApi(diagnostic = it, deviceFacts = com.korailmacro.app.korail.KorailDevice.facts(this@MainActivity)) })
                }
                diagnosticFeedback = environment + report
            } finally { diagnosticBusy = false; settingsBusy = false; if (currentPage == "settings") renderPage() }
        }
    }
    private fun testTelegram(draft: SettingsDraft) {
        val token = draft.token.trim(); val chat = draft.chat.trim()
        if (token.isBlank() || chat.isBlank()) { telegramFeedback = "토큰과 Chat ID를 입력하세요."; renderPage(); return }
        settingsBusy = true; telegramFeedback = "테스트 알림 전송 중…"; renderPage()
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) { TelegramNotifier(token, chat).send("코레일 매크로 테스트 알림입니다. 알림 연결을 확인했습니다.") }
                telegramFeedback = if (success) "테스트 알림 전송 완료" else "전송 실패 · 토큰, Chat ID, 봇 대화 시작 여부를 확인하세요."
            } finally { settingsBusy = false; if (currentPage == "settings") renderPage() }
        }
    }
    private fun showFullLog() {
        val log = text(ServiceBus.log.value, 13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val logScroll = ScrollView(this).apply { addView(log) }
        val dialog = AlertDialog.Builder(this).setTitle("실행 로그").setView(logScroll).setPositiveButton("닫기", null).create()
        val job = lifecycleScope.launch { ServiceBus.log.collect { log.text = it } }
        dialog.setOnDismissListener { job.cancel() }; dialog.show()
    }
    private fun batterySettings() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) { toast("이미 배터리 최적화에서 제외되어 있습니다."); return }
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }.onFailure { toast("기기 설정에서 배터리 제한을 해제하세요.") }
    }
    private fun notificationSettings() { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
