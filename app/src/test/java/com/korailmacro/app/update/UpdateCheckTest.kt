package com.korailmacro.app.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UpdateCheckTest {
    private val abcSha = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
    private fun json(
        versionCode: Any? = 20001, versionName: String? = "2.0.1",
        apkUrl: String? = "https://github.com/ppcciiss2-ux/korail-macro-updates/releases/download/android-2.0.1/KorailMacro-2.0.1.apk",
        sha256: String? = abcSha.lowercase(), notes: String? = "좌석 조회 개선",
    ) = listOf("versionCode" to versionCode, "versionName" to versionName, "apkUrl" to apkUrl, "sha256" to sha256, "notes" to notes)
        .filter { it.second != null }
        .joinToString(",", "{", "}") { (k, v) -> if (v is Number) "\"$k\":$v" else "\"$k\":\"$v\"" }

    private fun failure(result: CheckResult) = (result as CheckResult.Failed).message

    @Test fun parsesPublishedFormat() {
        val info = UpdateCheck.parse(json())
        assertEquals(20001L, info.versionCode)
        assertEquals("2.0.1", info.versionName)
        assertTrue(info.apkUrl.endsWith("/android-2.0.1/KorailMacro-2.0.1.apk"))
        assertEquals("좌석 조회 개선", info.notes)
    }
    @Test fun sha256IsNormalizedToUppercase() { assertEquals(abcSha, UpdateCheck.parse(json()).sha256) }
    @Test fun notesAreOptional() { assertEquals("", UpdateCheck.parse(json(notes = null)).notes) }
    @Test fun plainHttpApkIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { UpdateCheck.parse(json(apkUrl = "http://example.com/a.apk")) }
    }
    @Test fun malformedSha256IsRejected() {
        listOf(abcSha.dropLast(1), abcSha.dropLast(1) + "G", "").forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) { UpdateCheck.parse(json(sha256 = bad)) }
        }
    }
    @Test fun missingOrInvalidFieldsAreRejected() {
        listOf(json(versionCode = null), json(versionCode = 0), json(versionCode = "abc"), json(versionName = null),
            json(apkUrl = null), json(sha256 = null), "not json", "[]").forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) { UpdateCheck.parse(bad) }
        }
    }

    @Test fun higherVersionCodeIsAvailable() {
        val result = UpdateCheck.evaluate(200, json(versionCode = 20001), currentVersionCode = 20000)
        assertEquals("2.0.1", (result as CheckResult.Available).info.versionName)
    }
    @Test fun sameVersionCodeIsUpToDate() {
        assertTrue(UpdateCheck.evaluate(200, json(versionCode = 20000, versionName = "2.0.0"), 20000) is CheckResult.UpToDate)
    }
    @Test fun lowerVersionCodeNeverDowngrades() {
        assertTrue(UpdateCheck.evaluate(200, json(versionCode = 5, versionName = "1.4"), 20000) is CheckResult.UpToDate)
    }
    @Test fun missingInfoFileIsAFailure() { assertTrue(failure(UpdateCheck.evaluate(404, null, 20000)).contains("404")) }
    @Test fun serverErrorIsAFailure() { assertTrue(failure(UpdateCheck.evaluate(503, "", 20000)).contains("503")) }
    @Test fun emptyOrMalformedBodyIsAFailure() {
        assertTrue(failure(UpdateCheck.evaluate(200, "", 20000)).contains("비어"))
        assertTrue(failure(UpdateCheck.evaluate(200, json(apkUrl = "http://x/a.apk"), 20000)).contains("https"))
    }

    @Test fun sha256OfKnownInput() { assertEquals(abcSha, UpdateCheck.sha256Hex(ByteArrayInputStream("abc".toByteArray()))) }
    @Test fun copyVerifiedKeepsMatchingFile() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        UpdateCheck.copyVerified(ByteArrayInputStream("abc".toByteArray()), output, abcSha.lowercase()) { progress += it }
        assertEquals("abc", output.toString())
        assertEquals(3L, progress.last())
    }
    @Test fun copyVerifiedRejectsDifferentFile() {
        val error = assertThrows(IllegalStateException::class.java) {
            UpdateCheck.copyVerified(ByteArrayInputStream("abd".toByteArray()), ByteArrayOutputStream(), abcSha)
        }
        assertTrue(error.message!!.contains("SHA-256"))
    }
    @Test fun copyVerifiedStopsAtSizeLimit() {
        assertThrows(IllegalStateException::class.java) {
            UpdateCheck.copyVerified(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), abcSha, maxBytes = 10)
        }
    }
    @Test fun percentNeedsKnownLength() {
        assertEquals(50, UpdateCheck.percent(5, 10))
        assertEquals(100, UpdateCheck.percent(12, 10))
        assertNull(UpdateCheck.percent(5, -1))
    }
}
