package com.korailmacro.app.korail

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Explicit opt-in only. One anonymous search page; never logs in or reserves. */
class KorailReadOnlySmokeTest {
    @Test fun anonymousSearch() {
        assumeTrue(System.getenv("KORAIL_READ_ONLY_SMOKE") == "1")
        val date = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
        val trains = KorailApi().searchTrain("서울", "부산", date, "090000", 1, "000000")
        println("Anonymous query: date=$date, trains=${trains.size}")
        assertTrue("Expected at least one train on Seoul-Busan", trains.isNotEmpty())
    }
}
