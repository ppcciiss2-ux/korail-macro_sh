package com.korailmacro.app.korail

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConnectionDiagnosticsTest {
    private fun diagnose(handler: (Request) -> String): String = ConnectionDiagnostics.run(
        "2", "SECRET_MEMBER", "SECRET_PASSWORD", "서울", "부산", "20261101", "090000", 1,
        factory = { observer -> KorailApi(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build(), observer) })

    @Test fun successfulDiagnosticsSearchesOnceAndNeverReserves() {
        val paths = mutableListOf<String>()
        val report = diagnose {
            paths += it.url.encodedPath
            when {
                it.url.encodedPath.endsWith("code.do") -> """{"app.login.cphd":{"pwdAESCphd":"N"}}"""
                it.url.encodedPath.endsWith("login.Login") -> """{"h_msg_cd":"S200"}"""
                it.url.encodedPath.endsWith("ScheduleView") -> """{"strResult":"SUCC","trn_infos":[]}"""
                else -> error("Unexpected request")
            }
        }
        assertEquals(3, paths.size)
        assertFalse(paths.any { "TicketReservation" in it })
        assertTrue(report.contains("진단 완료"))
        assertFalse(report.contains("SECRET"))
    }

    @Test fun blockedBootstrapStopsBeforeLoginAndRedactsServerMessage() {
        var requests = 0
        val report = diagnose { requests++; """{"h_msg_cd":"-8005","h_msg_txt":"SECRET_MEMBER SECRET_PASSWORD"}""" }
        assertEquals(1, requests)
        assertTrue(report.contains("진단 중단: 초기 통신"))
        assertTrue(report.contains("-8005"))
        assertFalse(report.contains("SECRET"))
    }

    @Test fun loginRejectionStopsBeforeSearch() {
        var requests = 0
        val report = diagnose {
            requests++
            if (requests == 1) """{"app.login.cphd":{"pwdAESCphd":"N"}}"""
            else """{"h_msg_cd":"WRONG_PASSWORD","h_msg_txt":"SECRET_PASSWORD"}"""
        }
        assertEquals(2, requests)
        assertTrue(report.contains("진단 중단: 로그인"))
        assertFalse(report.contains("SECRET"))
    }

    @Test fun networkFailureDoesNotExposeExceptionText() {
        val report = diagnose { throw IOException("SECRET_PASSWORD") }
        assertTrue(report.contains("네트워크 연결 실패"))
        assertFalse(report.contains("SECRET"))
    }

    @Test fun blockedSearchReportsFirstFailureAndDoesNotRetry() {
        var requests = 0
        val report = diagnose {
            requests++
            when (requests) {
                1 -> """{"app.login.cphd":{"pwdAESCphd":"N"}}"""
                2 -> """{"h_msg_cd":"S200"}"""
                else -> """{"h_msg_cd":"-8005","h_msg_txt":"SECRET_MEMBER"}"""
            }
        }
        assertEquals(3, requests)
        assertTrue(report.contains("진단 중단: 열차 조회"))
        assertFalse(report.contains("SECRET"))
    }

    @Test fun arbitraryServerValuesAreNotReportedAsCodes() {
        assertNull(DiagnosticEvent.serverCode("""{"code":"member@example.com"}"""))
        assertNull(DiagnosticEvent.serverCode("not json"))
    }
}
