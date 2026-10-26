package com.korailmacro.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.korailmacro.app.update.AppUpdateInfo
import com.korailmacro.app.update.AppUpdater
import com.korailmacro.app.update.CheckResult
import com.korailmacro.app.update.UpdateActivity
import com.korailmacro.app.update.UpdateViewModel
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** 업데이트 화면 세 상태와 "앱을 켜도 확인하지 않음"을 네트워크 없이 확인한다. preview 앱에서만 실행한다. */
@RunWith(AndroidJUnit4::class)
class UpdateScreenTest {
    private val checks = AtomicInteger()
    private var next: CheckResult = CheckResult.Failed("")
    private val newer = AppUpdateInfo(20001, "2.0.1", "https://example.com/a.apk", "A".repeat(64), "좌석 조회 개선")

    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".preview")) { "UI tests must never change the regular app" }
        // 새 에뮬레이터에서는 MainActivity 의 알림 권한 창이 화면을 덮어 Espresso 가 멈춘다
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        UpdateViewModel.testChecker = { checks.incrementAndGet(); next }
    }
    @After fun reset() { UpdateViewModel.testChecker = null }

    @Test fun mainScreenNeverChecksUntilUpdateIsPressed() {
        next = CheckResult.UpToDate(newer.copy(versionCode = 1, versionName = "1.0"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            listOf("좌석 현황", "예약 결과", "예매 조건", "설정").forEach { onView(withText(it)).perform(click()) }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            assertEquals("앱 실행·탭 이동·복귀에서는 확인하지 않는다", 0, checks.get())
            onView(allOf(withText("업데이트"), isClickable())).perform(scrollTo(), click())
            onView(withText("앱이 최신입니다.")).check(matches(isDisplayed()))
            assertEquals(1, checks.get())
            pressBack()
        }
    }

    @Test fun upToDateShowsCheckMark() {
        next = CheckResult.UpToDate(newer.copy(versionCode = 1, versionName = "1.0"))
        ActivityScenario.launch(UpdateActivity::class.java).use {
            onView(withText("앱(${BuildConfig.VERSION_NAME})")).check(matches(isDisplayed()))
            onView(withText("✓")).check(matches(isDisplayed()))
            onView(withText("앱이 최신입니다.")).check(matches(isDisplayed()))
            onView(allOf(withText("업데이트"), isClickable())).check(doesNotExist())
        }
    }

    @Test fun newerVersionShowsVersionNotesAndUpdateButton() {
        next = CheckResult.Available(newer)
        ActivityScenario.launch(UpdateActivity::class.java).use {
            onView(withText("새 버전을 사용할 수 있습니다 (2.0.1)")).check(matches(isDisplayed()))
            onView(withText("좌석 조회 개선")).check(matches(isDisplayed()))
            onView(allOf(withText("업데이트"), isClickable())).check(matches(isDisplayed()))
        }
    }

    @Test fun installIntentHandsCachedApkToSystemInstaller() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apk = java.io.File(context.cacheDir, "updates").apply { mkdirs() }.resolve("KorailMacro-update.apk").apply { writeText("x") }
        val intent = AppUpdater(context).installIntent(apk)
        assertEquals("${context.packageName}.updates", intent.data?.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("x", context.contentResolver.openInputStream(intent.data!!)!!.use { it.reader().readText() })
        apk.delete()
    }

    @Test fun failureShowsReasonAndRecheckAsksAgain() {
        next = CheckResult.Failed("인터넷에 연결할 수 없습니다. 연결을 확인한 뒤 다시 확인하세요.")
        ActivityScenario.launch(UpdateActivity::class.java).use {
            onView(withText("업데이트를 확인하지 못했습니다.")).check(matches(isDisplayed()))
            onView(withText("인터넷에 연결할 수 없습니다. 연결을 확인한 뒤 다시 확인하세요.")).check(matches(isDisplayed()))
            onView(withText("앱이 최신입니다.")).check(doesNotExist())
            next = CheckResult.UpToDate(newer.copy(versionCode = 1, versionName = "1.0"))
            onView(withText("다시 확인")).perform(click())
            onView(withText("앱이 최신입니다.")).check(matches(isDisplayed()))
            assertEquals(2, checks.get())
        }
    }
}
