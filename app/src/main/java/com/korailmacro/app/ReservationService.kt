package com.korailmacro.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.korail.KorailException
import com.korailmacro.app.notify.TelegramNotifier
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.LocalDateTime

class ReservationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "자동 예약 실행 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, "예약 결과", NotificationManager.IMPORTANCE_HIGH))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KorailMacro:pollLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            if (job != null) {
                ServiceBus.update("중지 처리 중", "진행 중인 요청 결과를 확인하고 종료합니다.")
                job?.cancel()
            } else stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        startForeground(NOTIF_ID, notification("로그인 중", false))
        val options = ReservationOptions.fromJson(intent.getStringExtra(EXTRA_OPTIONS).orEmpty())
        val error = if (options == null) "예매 조건을 다시 선택하세요." else options.error()
        if (options == null || error != null) {
            ServiceBus.update("시작할 수 없음", error.orEmpty())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Snapshot all settings. UI edits must never change an active booking.
        val prefs = Prefs(this)
        val loginType = prefs.loginType
        val loginId = prefs.loginId
        val password = prefs.password
        val notifier = TelegramNotifier(prefs.telegramToken, prefs.telegramChatId)
        val seconds = prefs.pollIntervalSec.coerceAtLeast(1)
        val deadline = options.deadline()
        stopRequested = false
        ServiceBus.setRunning(true)
        ServiceBus.update("로그인 중", options.summary(), options)
        prefs.lastRunStatus = "이전 조회가 종료되었습니다. 조건 확인 후 다시 시작하세요."
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
        @OptIn(ExperimentalCoroutinesApi::class)
        val newJob = scope.launch(start = CoroutineStart.ATOMIC) {
            var completed = false
            var endTitle = "중지됨"
            try {
                ensureActive()
                val api = KorailApi()
                ServiceBus.append("자동 예약 시작: ${options.summary()}")
                api.login(loginType, loginId, password)
                ensureActive()
                fun timeExpired() = deadline != null && !LocalDateTime.now().isBefore(deadline)
                fun reLogin(): Boolean {
                    ServiceBus.update("재접속 중", "로그인 세션을 다시 연결합니다.")
                    return try {
                        api.login(loginType, loginId, password)
                        ServiceBus.append("재로그인 성공")
                        true
                    } catch (e: Exception) {
                        if (e is KorailException && e.stopPolling) throw e
                        ServiceBus.append("재로그인 실패: ${e.message}")
                        false
                    }
                }
                while (isActive) {
                    if (timeExpired()) { endTitle = "감시 종료시각 도달"; break }
                    val now = LocalDateTime.now()
                    val day = now.toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                    if (options.date < day || (options.date == day && options.end.isNotBlank() &&
                        now.toLocalTime() > ReservationOptions.parseTime(options.end))) {
                        endTitle = "열차 출발시간 범위 종료"; break
                    }
                    try {
                        ServiceBus.update("빈 좌석 조회 중", "${seconds}초 간격${deadline?.let { " · 감시 종료 ${it.toLocalTime().withSecond(0)}" }.orEmpty()}")
                        val trains = api.searchTrain(options.departure, options.arrival, options.date, options.start, options.adults, options.end)
                        ensureActive()
                        if (timeExpired()) { endTitle = "감시 종료시각 도달"; break }
                        val matching = trains.filter(options::targets)
                        val general = options.seat != KorailApi.SEAT_SPECIAL
                        val special = options.seat != KorailApi.SEAT_GENERAL
                        val target = matching.firstOrNull { (general && it.hasGeneralSeat) || (special && it.hasSpecialSeat) }
                        if (target != null) {
                            val seat = if (general && target.hasGeneralSeat) KorailApi.SEAT_GENERAL else KorailApi.SEAT_SPECIAL
                            ServiceBus.update("예약 시도 중", "${target.trainTypeName} ${target.trainNo} · ${ReservationOptions.seatLabel(seat)}")
                            var pnr: String? = null
                            for (attempt in 1..2) {
                                ensureActive()
                                if (timeExpired()) break
                                try {
                                    pnr = api.reserve(target, options.adults, seat)
                                    break
                                } catch (e: Exception) {
                                    if (e is KorailException && e.stopPolling) throw e
                                    ensureActive()
                                    ServiceBus.append("예약 시도 실패: ${e.message}")
                                    if (isSessionExpired(e.message) && attempt == 1 && !timeExpired()) {
                                        if (!reLogin()) break
                                    } else break
                                }
                            }
                            if (pnr != null) {
                                // A stop arriving during synchronous HTTP must not discard a successful booking.
                                completed = true
                                endTitle = "예약 성공"
                                val result = JSONObject().put("pnr", pnr).put("route", "${options.departure} → ${options.arrival}")
                                    .put("date", options.date).put("time", target.depTime)
                                    .put("train", "${target.trainTypeName} ${target.trainNo}")
                                    .put("seat", ReservationOptions.seatLabel(seat)).put("adults", options.adults)
                                    .put("createdAt", LocalDateTime.now().toString()).put("telegram", "전송 대기")
                                fun persistResult() {
                                    ServiceBus.result.value = result.toString()
                                    runCatching { prefs.lastResult = result.toString() }.onFailure {
                                        ServiceBus.append("예약은 완료됐으나 결과 저장 실패. 예약번호: $pnr")
                                    }
                                }
                                persistResult()
                                val message = "예약 성공!\n${options.departure} → ${options.arrival}\n${ReservationOptions.displayDate(options.date)} ${ReservationOptions.displayTime(target.depTime)}\n${target.trainTypeName} ${target.trainNo} · ${ReservationOptions.seatLabel(seat)} · 성인 ${options.adults}명\n예약번호: $pnr\n코레일 앱에서 결제 기한을 확인하고 직접 결제하세요."
                                ServiceBus.append(message)
                                ServiceBus.update("예약 성공", "예약 결과에서 예약번호를 확인하고 직접 결제하세요.")
                                // Separate ID so removing the ongoing notification preserves the result.
                                runCatching { getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification("예약 성공 · $pnr · 직접 결제 필요", true)) }
                                    .onFailure { ServiceBus.append("휴대폰 알림 표시 실패 · 예약 결과를 확인하세요.") }
                                result.put("telegram", if (runCatching { notifier.send(message) }.getOrDefault(false)) "전송 완료" else "전송 실패 또는 미설정")
                                persistResult()
                                ServiceBus.append("텔레그램 ${result.getString("telegram")}")
                                break
                            }
                        } else ServiceBus.append("빈 좌석 없음 · 대상 열차 ${matching.size}개 확인")
                        ServiceBus.update("다음 조회 대기", "${seconds}초 후 다시 조회 · 마지막 확인 ${java.time.LocalTime.now().withNano(0)}")
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        if (e is KorailException && e.stopPolling) throw e
                        // Once booked, no downstream notification/storage error may restart polling.
                        if (completed) { ServiceBus.append("예약 후 처리 오류: ${e.message}"); break }
                        ensureActive()
                        ServiceBus.append("조회 오류: ${e.message}")
                        ServiceBus.update("조회 오류 · 재시도 대기", e.message.orEmpty())
                        if (isSessionExpired(e.message) && !timeExpired()) reLogin()
                    }
                    ensureActive()
                    val remaining = deadline?.let { java.time.Duration.between(LocalDateTime.now(), it).toMillis().coerceAtLeast(0) }
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(ServiceBus.status.value.title, false))
                    delay(minOf(seconds * 1000L, remaining ?: Long.MAX_VALUE))
                }
            } catch (e: CancellationException) {
                endTitle = if (completed) "예약 성공" else "사용자가 중지함"
            } catch (e: Exception) {
                endTitle = if (completed) "예약 성공" else "실행 오류"
                ServiceBus.append("$endTitle: ${e.message}")
            } finally {
                ServiceBus.update(endTitle, if (completed) "예약 결과에서 직접 결제할 내역을 확인하세요." else if (stopRequested) "진행 중이던 요청 처리가 끝났습니다." else "로그에서 마지막 상태를 확인할 수 있습니다.")
                prefs.lastRunStatus = endTitle
                ServiceBus.append(endTitle)
                wakeLock?.let { if (it.isHeld) it.release() }
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    job = null
                    ServiceBus.setRunning(false)
                }
            }
        }
        job = newJob
        return START_NOT_STICKY
    }

    private fun isSessionExpired(message: String?) = message != null && ("로그아웃" in message || "다시 로그인" in message)
    private fun notification(text: String, result: Boolean): Notification {
        val open = PendingIntent.getActivity(this, if (result) 1 else 0,
            Intent(this, MainActivity::class.java).putExtra("page", if (result) "result" else "home"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, if (result) CHANNEL_RESULT else CHANNEL_ONGOING)
            .setContentTitle(if (result) "코레일 예약 결과" else "코레일 자동 예약")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open)
            .setAutoCancel(result).setOngoing(!result)
        if (!result) builder.addAction(android.R.drawable.ic_media_pause, "중지", PendingIntent.getService(this, 0,
            Intent(this, ReservationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }
    override fun onDestroy() { scope.cancel(); wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_STOP = "com.korailmacro.app.STOP"
        const val EXTRA_OPTIONS = "options"
        const val CHANNEL_ONGOING = "korail_macro_ongoing"
        const val CHANNEL_RESULT = "korail_macro_result"
        const val NOTIF_ID = 1001
        const val RESULT_ID = 1002
    }
}
