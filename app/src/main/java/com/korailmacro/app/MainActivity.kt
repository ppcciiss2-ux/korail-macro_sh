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
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.korail.Stations
import com.korailmacro.app.korail.Train
import com.korailmacro.app.korail.TrainType
import com.korailmacro.app.notify.TelegramNotifier
import com.korailmacro.app.update.UpdateActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var readyView: TextView? = null
    private var errorView: TextView? = null
    private var logExpanded = false
    private var settingsDraft: SettingsDraft? = null
    private var settingsBusy = false
    private var settingsFeedback = ""
    private var loginFeedback = ""
    private var telegramFeedback = ""
    private var verifiedCredentials: Triple<String, String, String>? = null
    private val bg get() = color(R.color.bgColor)
    private val surface get() = color(R.color.cardColor)
    private val ink get() = color(R.color.textPrimary)
    private val muted get() = color(R.color.textSecondary)
    private val accent get() = color(R.color.colorAccent)
    private val running get() = ServiceBus.running.value || busyStarting

    private data class SettingsDraft(var type: String, var id: String, var password: String,
        var token: String, var chat: String, var interval: Int, var theme: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        options = prefs.draft
        currentPage = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        if (savedInstanceState != null && savedInstanceState.containsKey("settingsType")) {
            settingsDraft = SettingsDraft(savedInstanceState.getString("settingsType").orEmpty(),
                savedInstanceState.getString("settingsId").orEmpty(), savedInstanceState.getString("settingsPassword").orEmpty(),
                savedInstanceState.getString("settingsToken").orEmpty(), savedInstanceState.getString("settingsChat").orEmpty(),
                savedInstanceState.getInt("settingsInterval", 5), savedInstanceState.getInt("settingsTheme", ThemePrefs.mode(this)))
        }
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
                launch { ServiceBus.result.collect { if (currentPage == "result") renderPage() } }
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
        if (::prefs.isInitialized) { refreshExpiredOptions(); updateReady() }
    }

    private fun refreshExpiredOptions() {
        val updated = options.refreshed(LocalDateTime.now(), running)
        if (updated == options) return
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
            outState.putInt("settingsTheme", it.theme)
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
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = MaterialButton(this).apply {
        text = value; isAllCaps = false; textSize = 14f; cornerRadius = dp(10)
        minHeight = dp(48); minimumWidth = 0
        insetTop = dp(3); insetBottom = dp(3)
        backgroundTintList = ColorStateList.valueOf(if (primary) accent else surface)
        setTextColor(if (primary) android.graphics.Color.WHITE else ink)
        setOnClickListener { action() }
    }
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

    private fun buildShell() {
        val root = column().apply { setBackgroundColor(bg) }
        val header = column().apply { setPadding(dp(16), dp(12), dp(16), 0) }
        header.add(caption("KORAIL MACRO / ${BuildConfig.VERSION_NAME}"))
        header.add(text("코레일 예매 매크로", 21f, true))
        val nav = LinearLayout(this)
        listOf("home" to "예매 조건", "seats" to "좌석 현황", "result" to "예약 결과", "settings" to "설정").forEach { (key, label) ->
            val b = button(label) { showPage(key) }.apply { textSize = 12f; setPadding(dp(2), 0, dp(2), 0) }
            tabs[key] = b
            nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        header.add(nav); root.add(header)
        content = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = column().apply { setPadding(dp(16), dp(8), dp(16), dp(12)); setBackgroundColor(bg) }
        footer.add(caption("빈 좌석 발견 시 자동 예약 · 결제는 직접 진행").apply { gravity = Gravity.CENTER })
        startButton = button("자동 예약 시작", true) { toggleRun() }
        footer.add(startButton); root.add(footer)
        setContentView(root)
    }

    private fun showPage(page: String) {
        currentPage = if (page in tabs) page else "home"
        renderPage(); scroll.scrollTo(0, 0)
        if (currentPage == "seats" && queriedOptions == null && !queryBusy) runQuery()
    }
    private fun renderPage() {
        if (!::content.isInitialized) return
        stateTitle = null; stateDetail = null; logView = null; readyView = null; errorView = null
        content.removeAllViews()
        tabs.forEach { (key, b) ->
            b.isSelected = key == currentPage
            b.setTextColor(if (key == currentPage) accent else muted)
            b.strokeWidth = if (key == currentPage) dp(1) else 0
            b.strokeColor = ColorStateList.valueOf(accent)
        }
        footer.visibility = if (currentPage == "home") View.VISIBLE else View.GONE
        startButton.text = if (running) "자동 조회 중지" else "자동 예약 시작"
        startButton.isEnabled = !busyStarting && ServiceBus.status.value.title != "중지 처리 중"
        when (currentPage) { "home" -> renderHome(); "seats" -> renderSeats(); "result" -> renderResult(); "settings" -> renderSettings() }
    }

    private fun changeOptions(value: ReservationOptions, keepSelection: Boolean = false) {
        options = if (keepSelection) value else value.copy(onlySelected = false, trainKeys = emptySet())
        prefs.draft = options
        if (!keepSelection) { queriedOptions = null; trains = emptyList(); queryTime = ""; queryError = "" }
        renderPage()
    }
    private fun activeOptions() = if (ServiceBus.running.value) ServiceBus.status.value.options ?: options else options
    private fun renderHome() {
        panel {
            stateTitle = text("", 16f, true).apply { setTextColor(accent) }; add(stateTitle!!)
            stateDetail = caption(""); add(stateDetail!!)
        }
        updateStatus()
        val editable = column()
        content.add(editable)
        panel(editable) {
            val dep = button("출발역\n${options.departure.ifBlank { "선택" }}") { stationPicker(true) }
            val arr = button("도착역\n${options.arrival.ifBlank { "선택" }}") { stationPicker(false) }
            row(this, dep, arr)
            add(button("⇄ 출발·도착 교환") { changeOptions(options.copy(departure = options.arrival, arrival = options.departure)) })
            divider(this)
            add(caption("가는 날"))
            add(button(ReservationOptions.displayDate(options.date)) { datePicker() })
            row(this,
                button("출발시간 이후\n${ReservationOptions.displayTime(options.start)}") { timePicker(options.start) { changeOptions(options.copy(start = it)) } },
                button("출발시간 까지\n${if (options.end.isBlank()) "선택 안함" else ReservationOptions.displayTime(options.end)}") {
                    timePicker(options.end, true) { changeOptions(options.copy(end = it)) }
                })
            add(caption("‘까지’를 비우면 시작시각 이후 전체 열차를 조회합니다."))
            errorView = text("", 13f).apply { setTextColor(color(R.color.colorError)); visibility = View.GONE }; add(errorView!!)
            row(this, button("☆ 조건 저장") { saveFavorite() }, button("즐겨찾기") { loadFavorite() })
        }
        panel(editable) {
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
            divider(this)
            add(caption("자동 조회 중지 · 열차 출발시간과 별개"))
            val stop = if (options.stopAt.isNotBlank()) "오늘 ${ReservationOptions.displayTime(options.stopAt)}" else if (options.stopAfterMinutes > 0) "시작 ${options.stopAfterMinutes}분 후" else "직접 중지할 때까지"
            add(button(stop) { choose("감시 종료", listOf("직접 중지", "30분 후", "1시간 후", "오늘 시각 지정")) {
                when (it) {
                    0 -> changeOptions(options.copy(stopAfterMinutes = 0, stopAt = ""), true)
                    1 -> changeOptions(options.copy(stopAfterMinutes = 30, stopAt = ""), true)
                    2 -> changeOptions(options.copy(stopAfterMinutes = 60, stopAt = ""), true)
                    else -> timePicker("230000") { time -> changeOptions(options.copy(stopAt = time, stopAfterMinutes = 0), true) }
                }
            } })
            add(caption(if (options.onlySelected) "감시 대상: 선택한 열차 ${options.trainKeys.size}개" else "감시 대상: 조건에 맞는 전체 열차"))
        }
        setEnabledTree(editable, !running)
        panel {
            add(text("실행 준비", 16f, true))
            readyView = caption(""); add(readyView!!)
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) add(button("배터리 최적화 제외 설정") { batterySettings() })
            if (!NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) add(button("알림 허용 설정") { notificationSettings() })
        }
        updateReady()
        content.add(button(if (logExpanded) "실행 로그 접기" else "실행 로그 펼치기") { logExpanded = !logExpanded; renderPage() })
        if (logExpanded) {
            content.add(button("크게 보기") { showFullLog() })
            logView = caption(ServiceBus.log.value).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
            content.add(logView!!)
        }
    }
    private fun updateStatus() {
        val s = ServiceBus.status.value
        if (busyStarting && s.title == "시작할 수 없음") { busyStarting = false; renderPage(); return }
        stateTitle?.text = if (s.title == "시작 대기") prefs.lastRunStatus else s.title
        stateDetail?.text = if (running) "${s.detail}\n적용 조건: ${activeOptions().summary()}" else "${s.detail}\n조회 간격 ${prefs.pollIntervalSec}초"
        if (::startButton.isInitialized) startButton.isEnabled = !busyStarting && s.title != "중지 처리 중"
    }
    private fun updateReady() {
        readyView?.text = listOf(
            if (prefs.loginVerified) "로그인 확인 ✓" else "로그인 확인 필요 · 설정에서 확인",
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) "알림 허용 ✓" else "알림 권한 필요",
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "배터리 설정 ✓" else "배터리 최적화 제외 필요"
        ).joinToString("\n")
    }
    private fun setEnabledTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabledTree(view.getChildAt(i), enabled)
    }
    private fun toggleRun() {
        if (running) { startService(Intent(this, ReservationService::class.java).setAction(ReservationService.ACTION_STOP)); return }
        val error = options.error() ?: if (prefs.loginId.isBlank() || prefs.password.isBlank()) "설정에서 로그인 정보를 입력하세요." else null
        if (error != null) { errorView?.apply { text = error; visibility = View.VISIBLE; requestRectangleOnScreen(android.graphics.Rect(0, 0, width, height)) }; return }
        prefs.draft = options; prefs.pushRecentRoute(options.departure, options.arrival)
        ServiceBus.clear(); busyStarting = true; renderPage()
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
    private fun stationPicker(departure: Boolean) {
        var stationDialog: AlertDialog? = null
        val root = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val search = EditText(this).apply { hint = "역 이름 또는 초성 검색"; setSingleLine() }
        root.add(search)
        val recent = prefs.recentRoutes()
        if (recent.isNotEmpty()) root.add(button("최근 구간") {
            choose("최근 구간", recent.map { "${it.first} → ${it.second}" }) { index ->
                changeOptions(options.copy(departure = recent[index].first, arrival = recent[index].second))
                stationDialog?.dismiss()
            }
        })
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, Stations.NAMES.toMutableList())
        val list = ListView(this).apply { this.adapter = adapter }
        val letters = HorizontalScrollView(this)
        val lettersRow = LinearLayout(this)
        Stations.AVAILABLE_INITIALS.forEach { initial -> lettersRow.addView(button(initial.toString()) {
            search.setText("")
            list.setSelection(Stations.firstIndexOf(initial))
        }) }
        letters.addView(lettersRow); root.add(letters)
        root.addView(list, LinearLayout.LayoutParams(-1, dp(280)))
        val dialog = AlertDialog.Builder(this).setTitle(if (departure) "출발역 선택" else "도착역 선택").setView(root).setNegativeButton("닫기", null).create()
        stationDialog = dialog
        list.setOnItemClickListener { _, _, position, _ ->
            val station = adapter.getItem(position).orEmpty()
            changeOptions(if (departure) options.copy(departure = station) else options.copy(arrival = station)); dialog.dismiss()
        }
        search.addTextChangedListener(watcher { query -> adapter.clear(); adapter.addAll(Stations.NAMES.filter { Stations.matches(it, query) }) })
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
            .setPositiveButton("저장") { _, _ -> if (name.text.isNotBlank()) { prefs.saveFavorite(name.text.toString().trim(), options); toast("조건을 저장했습니다.") } }.show()
    }
    private fun loadFavorite() {
        val favorites = prefs.favorites()
        if (favorites.isEmpty()) { toast("저장된 조건이 없습니다."); return }
        choose("즐겨찾기", favorites.map { it.first }) { i ->
            val (name, value) = favorites[i]
            AlertDialog.Builder(this).setTitle(name).setMessage(value.summary()).setPositiveButton("불러오기") { _, _ ->
                changeOptions(value.copy(date = options.date, onlySelected = false, trainKeys = emptySet()))
                toast("조건을 불러왔습니다. 가는 날을 확인하세요.")
            }.setNeutralButton("삭제") { _, _ -> prefs.removeFavorite(name) }.setNegativeButton("닫기", null).show()
        }
    }

    private fun runQuery() {
        if (queryBusy) return
        val config = activeOptions()
        val error = config.error(checkSelection = false, checkPast = !running) ?: if (prefs.loginId.isBlank() || prefs.password.isBlank()) "설정에서 로그인 정보를 입력하세요." else null
        if (error != null) { queryError = error; if (currentPage == "seats") renderPage(); return }
        val loginType = prefs.loginType; val id = prefs.loginId; val password = prefs.password
        queryBusy = true; queryError = ""; queriedOptions = config; trains = emptyList(); queryTime = ""
        if (currentPage == "seats") renderPage()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val api = KorailApi(); api.login(loginType, id, password)
                    api.searchTrain(config.departure, config.arrival, config.date, config.start, config.adults, config.end).filter(config::includes)
                }
                // Ignore a response if the user edited the form while this independent request was running.
                if (queriedOptions == config) { trains = result; queryTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (queriedOptions == config) queryError = "조회 실패: ${e.message}" }
            finally { queryBusy = false; if (currentPage == "seats") renderPage() }
        }
    }
    private fun renderSeats() {
        content.add(text(if (running) "실행 중인 조건으로 조회" else "현재 조건으로 조회", 17f, true))
        content.add(caption((queriedOptions ?: activeOptions()).summary()))
        content.add(button(if (queryBusy) "조회 중…" else "다시 조회") { runQuery() }.apply { isEnabled = !queryBusy })
        if (queryError.isNotBlank()) content.add(text(queryError).apply { setTextColor(color(R.color.colorError)) })
        if (queryTime.isNotBlank()) content.add(caption("마지막 조회 $queryTime · ${trains.size}개 열차"))
        if (!queryBusy && queryError.isBlank() && trains.isEmpty()) content.add(text("조건에 맞는 열차가 없습니다."))
        val selection = CheckBox(this).apply {
            text = "선택한 열차만 감시"; isChecked = activeOptions().onlySelected; minHeight = dp(48); isEnabled = !running && !queryBusy && queriedOptions != null
            setOnCheckedChangeListener { _, checked -> changeOptions(options.copy(onlySelected = checked), true) }
        }
        content.add(selection)
        content.add(caption(if (activeOptions().onlySelected) "${activeOptions().trainKeys.size}개 선택 · 빈 선택으로는 시작할 수 없습니다." else "현재 조건에 맞는 전체 열차를 감시합니다."))
        trains.forEach { train -> panel {
            add(text("${train.trainTypeName} ${train.trainNo}", 16f, true))
            add(text("${ReservationOptions.displayTime(train.depTime)} → ${ReservationOptions.displayTime(train.arrTime)}", 23f, true))
            add(caption("${train.depStationName} → ${train.arrStationName}"))
            row(this, text("일반실 ${if (train.hasGeneralSeat) "가능" else "매진"}").apply { setTextColor(if (train.hasGeneralSeat) color(R.color.colorSuccess) else muted) },
                text("특실 ${if (train.hasSpecialSeat) "가능" else "매진"}").apply { setTextColor(if (train.hasSpecialSeat) color(R.color.colorSuccess) else muted) })
            val key = ReservationOptions.key(train)
            add(CheckBox(this@MainActivity).apply {
                text = "이 열차 감시"; minHeight = dp(48); isChecked = key in activeOptions().trainKeys
                isEnabled = !running && options.onlySelected
                setOnCheckedChangeListener { _, checked -> changeOptions(options.copy(trainKeys = if (checked) options.trainKeys + key else options.trainKeys - key), true) }
            })
        } }
        content.add(caption("이 화면은 조회 전용입니다. 자동 예약은 예매 조건에서 시작하세요."))
    }

    private fun renderResult() {
        val result = runCatching { JSONObject(ServiceBus.result.value.ifBlank { prefs.lastResult }) }.getOrNull()
        if (result == null) { panel { add(text("아직 예약 결과가 없습니다.", 17f, true)); add(caption("자동 예약에 성공하면 마지막 결과를 여기에 보관합니다.")) }; return }
        panel {
            add(text("✓ 예약이 완료됐어요", 22f, true).apply { setTextColor(color(R.color.colorSuccess)) })
            add(caption("마지막 예약 결과 · ${result.optString("createdAt").replace('T', ' ').take(19)}"))
            divider(this)
            listOf("구간" to result.optString("route"), "출발" to "${ReservationOptions.displayDate(result.optString("date"))} ${ReservationOptions.displayTime(result.optString("time"))}",
                "열차 / 좌석" to "${result.optString("train")} / ${result.optString("seat")}", "인원" to "성인 ${result.optInt("adults")}명", "예약번호" to result.optString("pnr")).forEach { (label, value) ->
                add(caption(label)); add(text(value, 16f, true))
            }
            add(button("예약번호 복사") { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("예약번호", result.optString("pnr"))); toast("예약번호를 복사했습니다.") })
            divider(this); add(text("직접 결제가 필요해요", 17f, true))
            add(text("코레일 앱에서 예약 내역과 정확한 결제 기한을 확인하세요."))
            add(caption("텔레그램: ${result.optString("telegram")}"))
            add(caption("이 결과는 예약 성공 당시 기록입니다. 이후 결제·취소 상태는 코레일에서 확인하세요."))
        }
    }

    private fun renderSettings() {
        val draft = settingsDraft ?: SettingsDraft(prefs.loginType, prefs.loginId, prefs.password, prefs.telegramToken, prefs.telegramChatId,
            prefs.pollIntervalSec, ThemePrefs.mode(this)).also { settingsDraft = it }
        panel {
            add(text("코레일 로그인", 17f, true))
            val types = listOf(KorailApi.LOGIN_TYPE_EMAIL, KorailApi.LOGIN_TYPE_MEMBERSHIP, KorailApi.LOGIN_TYPE_PHONE)
            add(button(listOf("이메일", "회원번호", "휴대전화")[types.indexOf(draft.type).coerceAtLeast(0)]) {
                choose("로그인 방식", listOf("이메일", "회원번호", "휴대전화"), types.indexOf(draft.type)) { draft.type = types[it]; loginFeedback = ""; renderPage() }
            })
            field(this, "아이디", draft.id, if (draft.type == KorailApi.LOGIN_TYPE_EMAIL) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS else InputType.TYPE_CLASS_PHONE) { draft.id = it; loginFeedback = "" }
            field(this, "비밀번호", draft.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { draft.password = it; loginFeedback = "" }
            add(button(if (settingsBusy) "확인 중…" else "로그인 확인") { testLogin(draft) }.apply { isEnabled = !settingsBusy && !running })
            add(caption(loginFeedback.ifBlank { "입력한 계정으로 확인한 후 저장하세요." }))
        }
        panel {
            add(text("알림 연결", 17f, true))
            field(this, "텔레그램 봇 토큰", draft.token, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { draft.token = it }
            field(this, "Chat ID", draft.chat, InputType.TYPE_CLASS_TEXT) { draft.chat = it }
            add(button("테스트 알림 보내기") { testTelegram(draft) }.apply { isEnabled = !settingsBusy })
            add(caption(telegramFeedback.ifBlank { "예약 전에 알림 연결을 확인할 수 있습니다." }))
        }
        panel {
            add(text("조회 간격", 17f, true))
            ReservationOptions.INTERVALS.chunked(4).forEach { group -> row(this, *group.map { seconds ->
                button("${seconds}초", seconds == draft.interval) { draft.interval = seconds; renderPage() }
            }.toTypedArray()) }
            add(caption("조회가 끝난 뒤 다음 조회까지의 대기시간입니다. 1·3초는 요청 빈도가 높으므로 기본값은 5초입니다."))
            divider(this)
            val modes = listOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
            val names = listOf("시스템 설정", "라이트", "다크")
            add(button("화면 테마 · ${names[modes.indexOf(draft.theme).coerceAtLeast(0)]}") {
                choose("화면 테마", names, modes.indexOf(draft.theme)) { draft.theme = modes[it]; renderPage() }
            })
            add(caption("테마는 설정 저장 시 적용됩니다. 작성 중인 입력은 유지됩니다."))
            add(button("알림 권한 설정") { notificationSettings() })
            add(button("배터리 최적화 설정") { batterySettings() })
        }
        content.add(button("설정 저장", true) {
            val credentials = Triple(draft.type, draft.id.trim(), draft.password)
            val unchanged = credentials == Triple(prefs.loginType, prefs.loginId, prefs.password)
            prefs.loginVerified = verifiedCredentials == credentials || (unchanged && prefs.loginVerified)
            prefs.loginType = credentials.first; prefs.loginId = credentials.second; prefs.password = credentials.third
            prefs.telegramToken = draft.token.trim(); prefs.telegramChatId = draft.chat.trim(); prefs.pollIntervalSec = draft.interval
            ThemePrefs.setMode(this, draft.theme)
            settingsFeedback = if (running) "저장 완료 · 실행 중인 조회에는 적용되지 않으며 다음 시작부터 적용됩니다." else "설정을 저장했습니다."
            renderPage()
            AppCompatDelegate.setDefaultNightMode(draft.theme)
        }.apply { isEnabled = !settingsBusy })
        content.add(caption(settingsFeedback.ifBlank { if (running) "실행 중 설정 변경은 다음 시작부터 적용됩니다." else "확인 버튼은 계정 정보를 저장하지 않습니다." }))
        panel {
            add(text("앱", 17f, true))
            add(button("업데이트") { startActivity(Intent(this@MainActivity, UpdateActivity::class.java)) })
            add(caption("현재 버전 ${BuildConfig.VERSION_NAME} · 누를 때만 새 버전을 확인합니다."))
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) }
    }
    private fun field(parent: LinearLayout, label: String, value: String, type: Int, update: (String) -> Unit) {
        parent.add(caption(label))
        parent.add(EditText(this).apply {
            contentDescription = label; inputType = type; setSingleLine(); textSize = 16f; minHeight = dp(48)
            setText(value); addTextChangedListener(watcher(update))
        })
    }
    private fun testLogin(draft: SettingsDraft) {
        val credentials = Triple(draft.type, draft.id.trim(), draft.password)
        if (credentials.second.isBlank() || credentials.third.isBlank()) { loginFeedback = "아이디와 비밀번호를 입력하세요."; renderPage(); return }
        settingsBusy = true; loginFeedback = "로그인 확인 중…"; renderPage()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { KorailApi().login(credentials.first, credentials.second, credentials.third) }
                verifiedCredentials = credentials
                loginFeedback = "로그인 확인 성공 · 저장을 눌러 적용하세요."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { loginFeedback = "로그인 실패: ${e.message}" }
            finally { settingsBusy = false; if (currentPage == "settings") renderPage() }
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
