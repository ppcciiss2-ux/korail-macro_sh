package com.korailmacro.app.update

import android.app.Application
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateUiState {
    data object Checking : UpdateUiState
    data class UpToDate(val latestName: String) : UpdateUiState
    /** error: 직전 내려받기가 실패한 이유 */
    data class Available(val info: AppUpdateInfo, val error: String? = null) : UpdateUiState
    data class Downloading(val info: AppUpdateInfo, val percent: Int?) : UpdateUiState
    data class NeedsPermission(val info: AppUpdateInfo) : UpdateUiState
    data class ReadyToInstall(val info: AppUpdateInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/** 업데이트 화면의 상태. 화면에 들어올 때 한 번 확인하고, 그다음은 사용자가 누를 때만 확인한다. */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val updater = AppUpdater(application)
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Checking)
    val state: StateFlow<UpdateUiState> = _state
    private val installs = Channel<Intent>(Channel.CONFLATED)
    /** 화면이 열어야 할 시스템 설치 화면 */
    val installRequests: Flow<Intent> = installs.receiveAsFlow()
    private var apk: File? = null
    private var job: Job? = null

    init {
        updater.clearDownloads()
        check()
    }

    val busy get() = job?.isActive == true

    fun check() {
        if (busy) return
        _state.value = UpdateUiState.Checking
        job = viewModelScope.launch {
            _state.value = when (val result = testChecker?.invoke() ?: updater.check()) {
                is CheckResult.Available -> UpdateUiState.Available(result.info)
                is CheckResult.UpToDate -> UpdateUiState.UpToDate(result.latest.versionName)
                is CheckResult.Failed -> UpdateUiState.Failed(result.message)
            }
        }
    }

    fun update() {
        val info = (state.value as? UpdateUiState.Available)?.info ?: return
        if (busy) return
        _state.value = UpdateUiState.Downloading(info, null)
        job = viewModelScope.launch {
            try {
                apk = updater.download(info) { _state.value = UpdateUiState.Downloading(info, it) }
                install(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Available(info, e.message ?: "업데이트 파일을 받지 못했습니다.")
            }
        }
    }

    fun permissionIntent(): Intent = updater.unknownSourcesIntent()

    /** 설치 권한을 허용하고 돌아오면, 사용자가 이미 시작한 설치를 이어 간다 (새로 확인하지는 않는다) */
    fun onResume() {
        val waiting = state.value as? UpdateUiState.NeedsPermission ?: return
        if (updater.canInstallPackages()) install(waiting.info)
    }

    fun reopenInstaller() {
        (state.value as? UpdateUiState.ReadyToInstall)?.let { install(it.info) }
    }

    private fun install(info: AppUpdateInfo) {
        val file = apk ?: return
        if (!updater.canInstallPackages()) { _state.value = UpdateUiState.NeedsPermission(info); return }
        _state.value = UpdateUiState.ReadyToInstall(info)
        installs.trySend(updater.installIntent(file))
    }

    companion object {
        /** UI 테스트가 네트워크 없이 확인 결과를 정한다. 확인 횟수 검사에도 쓴다 */
        @VisibleForTesting @Volatile
        var testChecker: (suspend () -> CheckResult)? = null
    }
}
