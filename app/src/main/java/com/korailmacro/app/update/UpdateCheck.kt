package com.korailmacro.app.update

import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** android-update.json 한 건. 형식은 docs/RELEASE.md */
data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

sealed interface CheckResult {
    data class UpToDate(val latest: AppUpdateInfo) : CheckResult
    data class Available(val info: AppUpdateInfo) : CheckResult
    data class Failed(val message: String) : CheckResult
}

/** 업데이트 정보 해석·버전 비교·APK 검증. Android 에 기대지 않아 단위 테스트로 확인한다. */
object UpdateCheck {
    /** APK 크기 상한. 넘으면 받지 않는다 (지금 APK 는 약 7MB) */
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    private val SHA256_HEX = Regex("[0-9A-F]{64}")

    /** 형식이 틀리면 사용자에게 보일 문구를 담은 IllegalArgumentException */
    fun parse(json: String): AppUpdateInfo {
        val obj = try { JSONObject(json) } catch (e: JSONException) { throw IllegalArgumentException("JSON 이 아닙니다") }
        fun text(name: String) = obj.optString(name).trim().ifEmpty { throw IllegalArgumentException("$name 값이 없습니다") }
        val versionCode = obj.optLong("versionCode", 0)
        require(versionCode > 0) { "versionCode 값이 없습니다" }
        val info = AppUpdateInfo(
            versionCode = versionCode,
            versionName = text("versionName"),
            apkUrl = text("apkUrl"),
            sha256 = text("sha256").uppercase(),
            notes = obj.optString("notes").trim(),
        )
        require(info.apkUrl.startsWith("https://")) { "APK 주소가 https 가 아닙니다" }
        require(SHA256_HEX.matches(info.sha256)) { "sha256 형식이 잘못되었습니다" }
        return info
    }

    /** 정보 파일 응답을 결과로 바꾼다. versionCode 가 설치된 앱보다 클 때만 새 버전이다 (내려가지 않음) */
    fun evaluate(httpCode: Int, body: String?, currentVersionCode: Long): CheckResult {
        if (httpCode == 404) return CheckResult.Failed("업데이트 정보를 찾지 못했습니다 (HTTP 404).")
        if (httpCode !in 200..299) return CheckResult.Failed("업데이트 서버가 응답하지 않습니다 (HTTP $httpCode).")
        if (body.isNullOrBlank()) return CheckResult.Failed("업데이트 정보가 비어 있습니다.")
        val info = try { parse(body) } catch (e: IllegalArgumentException) {
            return CheckResult.Failed("업데이트 정보 형식이 잘못되었습니다: ${e.message}")
        }
        return if (info.versionCode > currentVersionCode) CheckResult.Available(info) else CheckResult.UpToDate(info)
    }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    /**
     * input 을 output 에 옮기며 SHA-256 을 계산한다. 크기 상한을 넘거나 해시가 expectedSha256 과 다르면
     * IllegalStateException. 호출한 쪽은 실패하면 output 파일을 지워야 한다.
     * onProgress 는 받은 바이트 수로 불린다 (취소 확인에도 쓴다).
     */
    fun copyVerified(
        input: InputStream,
        output: OutputStream,
        expectedSha256: String,
        maxBytes: Long = MAX_APK_BYTES,
        onProgress: (Long) -> Unit = {},
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= maxBytes) { "업데이트 파일이 너무 큽니다." }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            onProgress(total)
        }
        val actual = digest.digest().joinToString("") { "%02X".format(it) }
        check(actual.equals(expectedSha256, ignoreCase = true)) { "받은 파일의 SHA-256 이 맞지 않습니다." }
    }

    fun percent(received: Long, total: Long): Int? =
        if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else null
}
