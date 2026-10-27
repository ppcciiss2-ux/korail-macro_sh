package com.korailmacro.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.korailmacro.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateException(message: String) : Exception(message)

/**
 * 업데이트 전용 공개 저장소의 android-update.json 을 읽고, APK 를 받아 검증한 뒤 시스템 설치 화면을 연다.
 * 업데이트 화면(UpdateActivity)에서만 쓴다. 앱 실행·화면 복귀·백그라운드에서는 확인하지 않는다.
 */
class AppUpdater(context: Context) {
    private val context = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // GitHub 는 Releases 파일을 다른 https 주소로 넘긴다. https → http 로 넘기면 따르지 않는다
        .followSslRedirects(false)
        .build()
    private val folder get() = File(context.cacheDir, UPDATE_DIR)

    val currentVersionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "업데이트 정보 확인: $UPDATE_INFO_URL")
        val request = Request.Builder().url(UPDATE_INFO_URL).cacheControl(CacheControl.FORCE_NETWORK).build()
        try {
            http.newCall(request).execute().use { response ->
                UpdateCheck.evaluate(response.code, if (response.isSuccessful) response.body?.string() else null, currentVersionCode)
            }
        } catch (e: IOException) {
            CheckResult.Failed("인터넷에 연결할 수 없습니다. 연결을 확인한 뒤 다시 확인하세요.")
        }
    }

    /** SHA-256·패키지 이름·versionCode 가 맞는 APK 만 돌려준다. 실패하면 화면에 보일 문구를 담은 UpdateException */
    suspend fun download(info: AppUpdateInfo, onProgress: (Int?) -> Unit): File = withContext(Dispatchers.IO) {
        val job = coroutineContext
        clearDownloads()
        val target = File(folder.apply { mkdirs() }, "KorailMacro-update.apk")
        try {
            Log.i(TAG, "업데이트 파일 받기: ${info.apkUrl}")
            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("업데이트 파일을 받지 못했습니다 (HTTP ${response.code}).")
                if (!response.request.url.isHttps) throw UpdateException("안전하지 않은 주소로 넘어가 받지 않았습니다.")
                val body = response.body ?: throw UpdateException("업데이트 파일이 비어 있습니다.")
                val total = body.contentLength()
                if (total > UpdateCheck.MAX_APK_BYTES) throw UpdateException("업데이트 파일이 너무 큽니다.")
                var shown: Int? = -1
                target.outputStream().use { output ->
                    UpdateCheck.copyVerified(body.byteStream(), output, info.sha256) { received ->
                        job.ensureActive()
                        val percent = UpdateCheck.percent(received, total)
                        if (percent != shown) { shown = percent; onProgress(percent) }
                    }
                }
            }
            checkArchive(target, info)
            target
        } catch (e: Exception) {
            target.delete()
            throw when (e) {
                is CancellationException, is UpdateException -> e
                is IllegalStateException -> UpdateException(e.message ?: "받은 파일을 확인하지 못했습니다.")
                is IOException -> UpdateException("내려받는 중 연결이 끊겼습니다. 다시 시도하세요.")
                else -> e
            }
        }
    }

    fun clearDownloads() { folder.listFiles()?.forEach { it.delete() } }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 다른 앱이나 안내와 다른 버전이면 설치 화면을 열지 않는다 (서명은 Android 설치 화면이 확인한다) */
    private fun checkArchive(file: File, info: AppUpdateInfo) {
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw UpdateException("받은 파일이 Android 앱(APK)이 아닙니다.")
        if (archive.packageName != context.packageName) throw UpdateException("받은 파일이 이 앱의 업데이트가 아닙니다.")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (code != info.versionCode) throw UpdateException("받은 파일의 버전(versionCode $code)이 안내와 다릅니다.")
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val UPDATE_DIR = "updates"

        /** 업데이트 전용 공개 저장소 (docs/RELEASE.md). 게시 스크립트만 이 파일을 바꾼다 */
        const val UPDATE_INFO_URL =
            "https://raw.githubusercontent.com/ppcciiss2-ux/korail-macro-updates/main/android-update.json"
    }
}
