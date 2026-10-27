package com.korailmacro.app

import com.korailmacro.app.korail.Train
import com.korailmacro.app.korail.TrainType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ReservationOptionsTest {
    private val reopenTime = LocalDateTime.of(2026, 10, 26, 18, 20, 45)

    @Test fun expiredDateAndBothTimesBecomeCurrentMinute() {
        val saved = ReservationOptions(date = "20261021", start = "140000", end = "160000", onlySelected = true, trainKeys = setOf("old"))
        val updated = saved.refreshed(reopenTime)
        assertEquals("20261026", updated.date)
        assertEquals("182000", updated.start)
        assertEquals("182000", updated.end)
        assertFalse(updated.onlySelected)
        assertTrue(updated.trainKeys.isEmpty())
    }
    @Test fun todayClampsOnlyExpiredTime() {
        val updated = ReservationOptions(date = "20261026", start = "140000", end = "210000").refreshed(reopenTime)
        assertEquals("182000", updated.start)
        assertEquals("210000", updated.end)
    }
    @Test fun futureDateAndActiveRunAreUnchanged() {
        val future = ReservationOptions(date = "20261027", start = "140000", end = "160000")
        assertEquals(future, future.refreshed(reopenTime))
        val active = future.copy(date = "20261021")
        assertEquals(active, active.refreshed(reopenTime, running = true))
    }
    @Test fun midnightUpdatesDateAndPreservesFutureClockAndOpenEnd() {
        val updated = ReservationOptions(date = "20261025", start = "140000", end = "")
            .refreshed(LocalDateTime.of(2026, 10, 26, 0, 0))
        assertEquals("20261026", updated.date)
        assertEquals("140000", updated.start)
        assertEquals("", updated.end)
    }
    @Test fun sameMinuteRefreshDoesNotClearSelection() {
        val saved = ReservationOptions(date = "20261026", start = "182000", end = "182000", onlySelected = true, trainKeys = setOf("selected"))
        assertEquals(saved, saved.refreshed(reopenTime))
    }
    private val now = LocalDateTime.of(2026, 9, 7, 18, 0)
    private val options = ReservationOptions("서울", "부산", "20260908", "180000", "210000", types = setOf(TrainType.KTX))
    private fun train(time: String = "183000", name: String = "KTX", date: String = "20260908", no: String = "153") = Train(
        "100", name, "100", no, "서울", "0001", date, time, "부산", "0020", "211100", date,
        "1", "2", "1", "2", "11", "11", "예약 가능")

    @Test fun validFutureWindow() { assertNull(options.error(now)) }
    @Test fun malformedClockIsRejected() {
        assertNull(ReservationOptions.parseTime("240000"))
        assertNull(ReservationOptions.parseTime("186000"))
        assertNotNull(options.copy(start = "invalid").error(now))
    }
    @Test fun ongoingQueryCanUsePastStartWithoutChangingItsSnapshot() {
        assertNull(options.copy(date = "20260907", start = "170000").error(now, checkSelection = false, checkPast = false))
    }
    @Test fun sameStationsAreRejected() { assertNotNull(options.copy(arrival = "서울").error(now)) }
    @Test fun pastDepartureIsRejected() { assertNotNull(options.copy(date = "20260907", start = "175500").error(now)) }
    @Test fun sameMinuteIsAllowed() { assertNull(options.copy(date = "20260907").error(now.plusSeconds(30))) }
    @Test fun reversedWindowIsRejected() { assertNotNull(options.copy(end = "170000").error(now)) }
    @Test fun openEndedWindowIsAllowed() { assertNull(options.copy(end = "").error(now)) }
    @Test fun emptyTypesAreRejected() { assertNotNull(options.copy(types = emptySet()).error(now)) }
    @Test fun emptyExplicitSelectionCannotBook() {
        assertNotNull(options.copy(onlySelected = true).error(now))
        assertNull(options.copy(onlySelected = true).error(now, checkSelection = false))
    }
    @Test fun queryAndReservationShareWindowAndTypeFilters() {
        assertTrue(options.includes(train()))
        assertFalse(options.includes(train("213000")))
        assertFalse(options.includes(train("175500")))
        assertFalse(options.includes(train(name = "무궁화호")))
        assertFalse(options.includes(train(date = "20260909")))
        assertTrue(options.includes(train("210000")))
    }
    @Test fun specificTrainSelectionNeverFallsBackToAll() {
        val selected = options.copy(onlySelected = true, trainKeys = setOf(ReservationOptions.key(train())))
        assertTrue(selected.targets(train()))
        assertFalse(selected.targets(train(no = "157")))
        assertFalse(selected.copy(trainKeys = emptySet()).targets(train()))
        assertTrue(options.targets(train(no = "157")))
    }
    @Test fun selectionKeysIncludeDateAndDepartureTime() {
        assertNotEquals(ReservationOptions.key(train()), ReservationOptions.key(train(date = "20260909")))
        assertNotEquals(ReservationOptions.key(train()), ReservationOptions.key(train("190000")))
    }
    @Test fun durationAndClockDeadlineAreSeparateFromTravelWindow() {
        assertNull(options.deadline(now))
        assertEquals(now.plusMinutes(30), options.copy(stopAfterMinutes = 30).deadline(now))
        assertEquals(now.withHour(23), options.copy(stopAt = "230000").deadline(now))
        assertNotNull(options.copy(stopAt = "170000").error(now))
    }
    @Test fun optionsRoundTripPreservesSelectionAndStopCondition() {
        val value = options.copy(onlySelected = true, trainKeys = setOf("20260908:100:153:183000"), stopAfterMinutes = 30)
        assertEquals(value, ReservationOptions.fromJson(value.json()))
        assertNull(ReservationOptions.fromJson("bad input"))
    }
    @Test fun intervalsIncludeRequestedOneAndThreeSeconds() {
        assertEquals(listOf(1, 3, 5, 10, 15, 30, 60), ReservationOptions.INTERVALS)
    }
}
