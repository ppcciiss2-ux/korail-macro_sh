package com.korailmacro.app.update

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.korailmacro.app.BuildConfig
import com.korailmacro.app.R
import com.korailmacro.app.ServiceBus
import kotlinx.coroutines.launch

/** [설정] → [업데이트]. AdGuard 업데이트 화면처럼 "앱(버전) / 상태"를 한 줄로 보여 준다. */
class UpdateActivity : AppCompatActivity() {
    private lateinit var model: UpdateViewModel
    private lateinit var recheck: MaterialButton
    private lateinit var mark: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: MaterialButton
    private var onAction: () -> Unit = {}
    private val ink get() = color(R.color.textPrimary)
    private val muted get() = color(R.color.textSecondary)
    private val accent get() = color(R.color.colorAccent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[UpdateViewModel::class.java]
        buildViews()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch { model.installRequests.collect(::openInstaller) }
            }
        }
    }

    override fun onResume() { super.onResume(); model.onResume() }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun flatButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; textSize = 15f; minHeight = dp(48); minimumWidth = 0; setTextColor(ink)
        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun buildViews() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.bgColor)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(8), dp(8)) }
        toolbar.addView(flatButton("←") { finish() }.apply { textSize = 22f; contentDescription = "뒤로" }, LinearLayout.LayoutParams(dp(56), -2))
        toolbar.addView(text(21f, ink, true).apply { text = "업데이트" }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        recheck = flatButton("다시 확인") { model.check() }.apply { setTextColor(accent) }
        toolbar.addView(recheck)
        root.addView(toolbar)

        val row = LinearLayout(this).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val slot = FrameLayout(this)
        mark = text(26f, muted).apply { gravity = Gravity.CENTER }
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        slot.addView(mark, FrameLayout.LayoutParams(-1, -1))
        slot.addView(spinner, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        row.addView(slot, LinearLayout.LayoutParams(dp(48), dp(48)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(18f, ink).apply { text = "앱(${BuildConfig.VERSION_NAME})" })
        status = text(15f, muted).apply { setPadding(0, dp(2), 0, 0) }
        detail = text(14f, muted).apply { setPadding(0, dp(6), 0, 0) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        action = MaterialButton(this).apply {
            isAllCaps = false; textSize = 15f; cornerRadius = dp(10); minHeight = dp(48)
            backgroundTintList = ColorStateList.valueOf(accent); setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { onAction() }
        }
        body.addView(status); body.addView(detail)
        body.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(action, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        root.addView(row)
        root.addView(View(this).apply { setBackgroundColor(color(R.color.borderColor)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(80) })
        root.addView(text(13f, muted).apply {
            text = "업데이트는 이 화면에서만 확인합니다. 받은 파일은 SHA-256 이 맞을 때만 설치 화면을 엽니다."
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        setContentView(root)
    }

    private fun render(state: UpdateUiState) {
        var symbol = ""; var symbolColor = muted; var line = ""; var note = ""; var noteColor = muted
        var progress: Int? = null; var showBar = false; var label: String? = null
        when (state) {
            UpdateUiState.Checking -> line = "업데이트를 확인하는 중…"
            is UpdateUiState.UpToDate -> { symbol = "✓"; line = "앱이 최신입니다." }
            is UpdateUiState.Available -> {
                symbol = "↓"; symbolColor = accent
                line = "새 버전을 사용할 수 있습니다 (${state.info.versionName})"
                note = listOfNotNull(state.info.notes.ifBlank { null },
                    if (ServiceBus.running.value) "자동 예약이 실행 중입니다. 설치하면 자동 예약이 멈춥니다." else null).joinToString("\n")
                state.error?.let { note = listOf(note, it).filter(String::isNotBlank).joinToString("\n"); noteColor = color(R.color.colorError) }
                label = "업데이트"; onAction = model::update
            }
            is UpdateUiState.Downloading -> {
                line = state.percent?.let { "${state.info.versionName} 내려받는 중… $it%" } ?: "${state.info.versionName} 내려받는 중…"
                showBar = true; progress = state.percent
            }
            is UpdateUiState.NeedsPermission -> {
                symbol = "!"; symbolColor = color(R.color.colorError)
                line = "설치 권한이 필요합니다"
                note = "설정에서 이 앱의 '출처를 알 수 없는 앱 설치'를 허용하고 돌아오면 설치 화면이 열립니다."
                label = "권한 설정"; onAction = { startActivity(model.permissionIntent()) }
            }
            is UpdateUiState.ReadyToInstall -> {
                symbol = "↓"; symbolColor = accent
                line = "${state.info.versionName} 설치 화면에서 [업데이트]를 누르세요"
                note = "설치가 끝나면 앱을 다시 여세요."
                label = "설치 화면 열기"; onAction = model::reopenInstaller
            }
            is UpdateUiState.Failed -> {
                symbol = "!"; symbolColor = color(R.color.colorError)
                line = "업데이트를 확인하지 못했습니다."
                note = state.message; noteColor = color(R.color.colorError)
            }
        }
        val waiting = state is UpdateUiState.Checking || state is UpdateUiState.Downloading
        spinner.visibility = if (waiting) View.VISIBLE else View.GONE
        mark.visibility = if (waiting) View.GONE else View.VISIBLE
        mark.text = symbol; mark.setTextColor(symbolColor)
        status.text = line
        detail.text = note; detail.setTextColor(noteColor); detail.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
        bar.visibility = if (showBar) View.VISIBLE else View.GONE
        bar.isIndeterminate = progress == null; bar.progress = progress ?: 0
        action.visibility = if (label == null) View.GONE else View.VISIBLE
        action.text = label.orEmpty()
        recheck.isEnabled = !waiting
    }

    private fun openInstaller(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "설치 화면을 열지 못했습니다.", Toast.LENGTH_LONG).show() }
    }
}
