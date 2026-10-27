package com.korailmacro.app.korail

import org.json.JSONObject

/**
 * Adapted from korail-mobile-api v3.0.0, _core/dynapath.py.
 * Copyright (c) 2026 yakisoba0728, Apache-2.0. See THIRD_PARTY_NOTICES.md.
 * Reports native SDK unavailability; does not invent a device identity or signing key.
 */
internal object MobileCompatibility {
    const val diagnosticToken = "2!v=2&es=165489088780120535&st=Android&sv=2.0.1&ai=korailplus"
}

internal object KorailResponse {
    private val stopCodes = setOf("-1203", "-1406", "-2000", "-8005", "-8201", "-8202", "-8203", "-8204")

    fun decode(status: Int, text: String): JSONObject {
        val json = try { JSONObject(text) } catch (_: Exception) {
            throw KorailException("서버 응답 형식을 확인할 수 없습니다.")
        }
        val code = listOf("h_msg_cd", "code", "errorCode").map { json.optString(it) }
            .firstOrNull { it in stopCodes }
        if (code != null) {
            val message = json.optString("h_msg_txt").ifBlank { json.optString("message") }
                .ifBlank { "서버가 요청을 제한했습니다." }
            throw KorailException("$code: $message", stopPolling = true)
        }
        if (status !in 200..299) throw KorailException("서버 연결 오류 (HTTP $status)", stopPolling = status in setOf(401, 403, 429))
        return json
    }
}
