package com.korailmacro.app.korail

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class KorailCompatibilityTest {
    private val train = Train("100", "KTX", "100", "001", "서울", "0001", "20261027", "090000",
        "부산", "0020", "120000", "20261027", "1", "2", "1", "2", "11", "11", "예약 가능")

    private fun client(handler: (Request) -> String): KorailApi = KorailApi(OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build())

    private fun assertCommon(request: Request, protected: Boolean = true) {
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("250722001", fields["Version"])
        assertEquals("7.0.10", fields["AppVersion"])
        assertEquals("korailtalk", request.header("User-Agent"))
        if (protected) assertEquals("2!v=2&es=165489088780120535&st=Android&sv=2.0.1&ai=korailplus", request.header("x-dynapath-m-token"))
    }

    @Test fun loginBootstrapAndProtectedLoginUseCompatibleFields() {
        var requests = 0
        val api = client { request ->
            requests++
            val bootstrap = request.url.encodedPath.endsWith("code.do")
            assertCommon(request, !bootstrap)
            if (bootstrap) """{"app.login.cphd":{"pwdAESCphd":"N"}}"""
            else """{"strResult":"SUCC","h_msg_cd":"S200"}"""
        }
        api.login("2", "test-member", "test-password")
        assertEquals(2, requests)
    }

    @Test fun queryPreservesSeatAvailability() {
        val api = client { request ->
            assertCommon(request)
            """{"strResult":"SUCC","trn_infos":{"trn_info":{"h_trn_no":"001","h_gen_rsv_cd":"11","h_spe_rsv_cd":"13"}}}"""
        }
        val result = api.searchTrain("서울", "부산", "20261027", "090000", 1)
        assertEquals(1, result.size)
        assertTrue(result.single().hasGeneralSeat)
        assertFalse(result.single().hasSpecialSeat)
    }

    @Test fun reservationUsesNewHeaderAndReturnsPnr() {
        val api = client { request ->
            assertCommon(request)
            """{"strResult":"SUCC","h_pnr_no":"TEST-PNR"}"""
        }
        assertEquals("TEST-PNR", api.reserve(train, 1, "1"))
    }

    @Test fun updateRequiredStopsEvenWithHttp200() {
        for (body in listOf("""{"strResult":"FAIL","h_msg_cd":"-8204"}""", """{"code":-2000}""")) {
            val error = assertThrows(KorailException::class.java) { KorailResponse.decode(200, body) }
            assertTrue(error.stopPolling)
        }
    }

    @Test fun lostBookingResponseIsTerminalAndSentOnce() {
        var requests = 0
        val api = client { requests++; throw IOException("connection lost") }
        val error = assertThrows(KorailException::class.java) { api.reserve(train, 1, "1") }
        assertTrue(error.stopPolling)
        assertEquals(1, requests)
    }

    @Test fun missingBookingNumberIsTerminal() {
        val api = client { """{"strResult":"SUCC"}""" }
        assertTrue(assertThrows(KorailException::class.java) { api.reserve(train, 1, "1") }.stopPolling)
    }

    @Test fun soldOutRemainsAnExplicitRetryableRejection() {
        val api = client { """{"strResult":"FAIL","h_msg_txt":"매진"}""" }
        assertFalse(assertThrows(KorailException::class.java) { api.reserve(train, 1, "1") }.stopPolling)
    }
}
