package com.korailmacro.app.korail

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

internal enum class DiagnosticStage(val label: String) {
    BOOTSTRAP("초기 통신"), LOGIN("로그인"), SEARCH("열차 조회"), OTHER("기타 요청");
    companion object {
        fun fromPath(path: String) = when {
            path.endsWith("common.code.do") -> BOOTSTRAP
            path.endsWith("login.Login") -> LOGIN
            path.endsWith("seatMovie.ScheduleView") -> SEARCH
            else -> OTHER
        }
    }
}

/** Only allowlisted metadata: no body, credentials, headers, cookies or server text. */
internal data class DiagnosticEvent(val stage: DiagnosticStage, val http: Int? = null, val code: String? = null) {
    companion object {
        fun serverCode(body: String): String? = runCatching {
            val json = JSONObject(body)
            listOf("h_msg_cd", "code", "errorCode").map { json.optString(it) }
                .firstOrNull { it.matches(Regex("(?:-[0-9]{1,6}|[A-Z]{1,5}[0-9]{1,12})")) }
        }.getOrNull()
    }
}

internal object ConnectionDiagnostics {
    fun run(type: String, id: String, password: String, dep: String, arr: String, date: String,
        time: String, adults: Int, checkActive: () -> Unit = {},
        factory: (((DiagnosticEvent) -> Unit) -> KorailApi) = { KorailApi(diagnostic = it) }): String {
        val lines = mutableListOf<String>()
        var stage = DiagnosticStage.BOOTSTRAP
        val api = factory { event ->
            checkActive()
            stage = event.stage
            if (event.http != null) lines += "${stage.label}: HTTP ${event.http} · 코드 ${event.code ?: "없음"}"
        }
        try {
            api.login(type, id, password)
            lines += "로그인 처리 성공"
            val count = api.diagnosticSearch(dep, arr, date, time, adults)
            lines += "조회 처리 성공 · 열차 ${count}개"
            lines += "진단 완료 · 예약 가능 여부는 검사하지 않았습니다."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = when (e) {
                is SSLException -> "보안 연결 실패"
                is IOException -> "네트워크 연결 실패"
                is KorailException -> if (e.stopPolling) "서버 제한 또는 응답 확인 필요" else "서버 응답 또는 로그인 처리 실패"
                else -> "응답 처리 실패"
            }
            lines += "진단 중단: ${stage.label} · $reason"
        }
        return lines.joinToString("\n")
    }
}
