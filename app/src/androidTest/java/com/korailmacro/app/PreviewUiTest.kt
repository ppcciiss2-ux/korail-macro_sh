package com.korailmacro.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.korailmacro.app.korail.TrainType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Offline UI tests only. Run exclusively on the separately installed preview application. */
@RunWith(AndroidJUnit4::class)
class PreviewUiTest {
    private lateinit var prefs: Prefs
    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".preview")) { "UI tests must never change the regular app" }
        prefs = Prefs(context)
        prefs.loginId = ""; prefs.password = ""; prefs.telegramToken = ""; prefs.telegramChatId = ""
        prefs.pollIntervalSec = 5; prefs.lastResult = ""; prefs.loginVerified = false
        prefs.draft = ReservationOptions("서울", "부산", LocalDate.now().plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE),
            "180000", "210000", types = setOf(TrainType.KTX))
        ServiceBus.setRunning(false); ServiceBus.result.value = ""; ServiceBus.update("시작 대기", "", null)
    }

    @Test fun allTabsAndEmptyResultAreVisible() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("예매 조건")).check(matches(isDisplayed()))
            onView(withText("좌석 현황")).check(matches(isDisplayed()))
            onView(withText("예약 결과")).perform(click())
            onView(withText("아직 예약 결과가 없습니다.")).check(matches(isDisplayed()))
        }
    }
    @Test fun intervalOneAndThreePersistAcrossRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("설정")).perform(click())
            onView(withText("1초")).perform(scrollTo(), click())
            onView(withText("설정 저장")).perform(scrollTo(), click())
            assertEquals(1, prefs.pollIntervalSec)
            onView(withText("3초")).perform(scrollTo(), click())
            onView(withText("설정 저장")).perform(scrollTo(), click())
            scenario.recreate()
            assertEquals(3, prefs.pollIntervalSec)
        }
    }
    @Test fun swapUpdatesPersistedDraft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("⇄ 출발·도착 교환")).perform(scrollTo(), click())
            assertEquals("부산", prefs.draft.departure)
            assertEquals("서울", prefs.draft.arrival)
        }
    }
    @Test fun queryWithoutAccountShowsValidationWithoutNetwork() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("좌석 현황")).perform(click())
            onView(withText("설정에서 로그인 정보를 입력하세요.")).check(matches(isDisplayed()))
        }
    }
    @Test fun startWithoutAccountIsBlocked() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("자동 예약 시작")).perform(click())
            onView(withText("설정에서 로그인 정보를 입력하세요.")).check(matches(isDisplayed()))
            assertEquals(false, ServiceBus.running.value)
        }
    }
    @Test fun unsavedIntervalDoesNotChangeSavedSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("설정")).perform(click())
            onView(withText("1초")).perform(scrollTo(), click())
            scenario.recreate()
            assertEquals(5, prefs.pollIntervalSec)
            onView(withText("설정 저장")).perform(scrollTo(), click())
            assertEquals(1, prefs.pollIntervalSec)
        }
    }
}
