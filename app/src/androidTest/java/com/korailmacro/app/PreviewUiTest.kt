package com.korailmacro.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
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
        prefs.favorites().forEach { prefs.removeFavorite(it.first) }
        ServiceBus.setRunning(false); ServiceBus.result.value = ""; ServiceBus.seenResult = ""; ServiceBus.update("시작 대기", "", null)
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
            onView(withText("설정 저장")).perform(click()) // fixed bottom bar, no longer inside the scrolling page
            assertEquals(1, prefs.pollIntervalSec)
            onView(withText("3초")).perform(scrollTo(), click())
            onView(withText("설정 저장")).perform(click())
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
            onView(withText("설정 저장")).perform(click())
            assertEquals(1, prefs.pollIntervalSec)
        }
    }
    @Test fun unsavedSettingsAreMarkedUntilSaved() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("설정")).perform(click())
            onView(withText("1초")).perform(scrollTo(), click())
            onView(withText("저장하지 않은 변경이 있습니다.")).check(matches(isDisplayed()))
            onView(withText("설정 •")).check(matches(isDisplayed()))
            onView(withText("설정 저장")).perform(click())
            onView(withText("설정을 저장했습니다.")).check(matches(isDisplayed()))
            onView(withText("설정")).check(matches(isDisplayed()))
        }
    }
    @Test fun startButtonIsAvailableOnSeatsTab() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("좌석 현황")).perform(click())
            onView(withText("자동 예약 시작")).check(matches(isDisplayed()))
            onView(withText("예약 결과")).perform(click())
            onView(withText("자동 예약 시작")).check(doesNotExist())
        }
    }
    @Test fun routeEditDropsSelectedTrainsWithoutFallingBackToAllTrains() {
        prefs.draft = prefs.draft.copy(onlySelected = true, trainKeys = setOf("selected"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("⇄ 출발·도착 교환")).perform(scrollTo(), click())
            assertEquals(true, prefs.draft.onlySelected)
            assertEquals(emptySet<String>(), prefs.draft.trainKeys)
            onView(withText("자동 예약 시작")).perform(click())
            onView(withText("좌석 현황에서 예약할 열차를 하나 이상 선택하세요.")).check(matches(isDisplayed()))
            assertEquals(false, ServiceBus.running.value)
            onView(withText("전체 열차로 변경")).perform(scrollTo(), click())
            assertEquals(false, prefs.draft.onlySelected)
        }
    }
    @Test fun backFromAnotherTabReturnsToConditions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("예약 결과")).perform(click())
            pressBack()
            onView(withText("⇄ 출발·도착 교환")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
    @Test fun missingLoginIsAButtonThatOpensSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("코레일 로그인 확인  ›")).perform(scrollTo(), click())
            onView(withText("코레일 로그인")).check(matches(isDisplayed()))
        }
    }
    @Test fun startButtonShowsWhatWillBeBooked() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withSubstring("서울 → 부산 · ")).check(matches(isDisplayed()))
            onView(withSubstring("18:00~21:00 · 일반실 1명 · 전체 열차")).check(matches(isDisplayed()))
        }
    }
    @Test fun sameStationsAreFlaggedNextToTheStationsBeforeStarting() {
        prefs.draft = prefs.draft.copy(arrival = "서울")
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("출발역과 도착역을 다르게 선택하세요.")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
    @Test fun hourAndDayAreOneTapChoices() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("출발시간 이후\n18:00")).perform(scrollTo(), click())
            onView(withText("19:00")).perform(click())
            assertEquals("190000", prefs.draft.start)
            onView(withText("모레")).perform(scrollTo(), click())
            assertEquals(LocalDate.now().plusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE), prefs.draft.date)
        }
    }
    @Test fun pickingDepartureOnAFreshTripContinuesToArrival() {
        prefs.draft = prefs.draft.copy(departure = "", arrival = "")
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("출발역\n선택")).perform(scrollTo(), click())
            onView(withText("대전")).perform(scrollTo(), click())
            onView(withText("도착역 선택")).check(matches(isDisplayed()))
            onView(withText("대전")).check(doesNotExist()) // the other end of the trip is not offered again
            onView(withText("부산")).perform(scrollTo(), click())
            assertEquals("대전", prefs.draft.departure)
            assertEquals("부산", prefs.draft.arrival)
        }
    }
    @Test fun deletingAFavoriteAsksFirst() {
        prefs.saveFavorite("퇴근", prefs.draft)
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("즐겨찾기")).perform(scrollTo(), click())
            onView(withText("퇴근")).perform(click())
            onView(withText("삭제")).perform(click())
            assertEquals(1, prefs.favorites().size)
            onView(withText("지우기")).perform(click())
            assertEquals(0, prefs.favorites().size)
        }
    }
    @Test fun departedBookingIsShownAsPastAndCanBeCleared() {
        prefs.lastResult = """{"pnr":"12345","route":"서울 → 부산","date":"20260101","time":"180000","train":"KTX 153","seat":"일반실","adults":1,"createdAt":"2026-01-01T10:00:00","telegram":"전송 완료"}"""
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("예약 결과")).perform(click())
            onView(withText("지난 예약 기록")).check(matches(isDisplayed()))
            onView(withText("코레일톡 열기")).check(doesNotExist())
            onView(withText("기록 지우기")).perform(scrollTo(), click())
            onView(withText("지우기")).perform(click())
            onView(withText("아직 예약 결과가 없습니다.")).check(matches(isDisplayed()))
            assertEquals("", prefs.lastResult)
        }
    }
    @Test fun secretFieldsCanBeShown() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("설정")).perform(click())
            onView(withText("비밀번호 보기")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("텔레그램 봇 토큰 보기")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
    @Test fun savedConditionLoadsWithOneTap() {
        prefs.saveFavorite("퇴근", prefs.draft.copy(departure = "대전", arrival = "동대구"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("★ 퇴근")).perform(scrollTo(), click())
            assertEquals("대전", prefs.draft.departure)
            assertEquals("동대구", prefs.draft.arrival)
        }
    }
    @Test fun passengerEditKeepsSelectedTrains() {
        prefs.draft = prefs.draft.copy(onlySelected = true, trainKeys = setOf("selected"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("성인 1명")).perform(scrollTo(), click())
            onView(withText("2명")).perform(click())
            assertEquals(2, prefs.draft.adults)
            assertEquals(setOf("selected"), prefs.draft.trainKeys)
        }
    }
}
