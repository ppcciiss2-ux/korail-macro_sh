package com.korailmacro.app.korail

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DeclarationTokenTest {
    @Test fun matchesUpstreamNativeRecordedVectorsAtZeroAndNonzeroNonce() {
        val text = javaClass.getResourceAsStream("/declaration-native-vectors.json")!!.bufferedReader().use { it.readText() }
        val cases = JSONArray(text)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONArray(i)
            assertEquals(case.getString(2), DeclarationToken.encode(case.getString(1).toByteArray(Charsets.US_ASCII), case.getInt(0)))
        }
    }

    @Test fun realDeviceFieldsChangeTokenAndClockDoesNotUseDefaultTime() {
        val facts = DeviceFacts("16", "Pixel 9", "Google", "tokay", 36, true, "1234567890abcdef")
        val token = DeclarationToken.generate(facts, 1700000000000L, -1)
        assertTrue(token.matches(Regex("201[A-Za-z0-9]+")))
        assertNotEquals(token, DeclarationToken.generate(facts.copy(id = "abcdef1234567890"), 1700000000000L, -1))
        assertNotEquals(token, DeclarationToken.generate(facts, 1700000000001L, -1))
    }

    @Test fun invalidDeviceIdFailsBeforeNetwork() {
        assertThrows(IllegalArgumentException::class.java) {
            DeclarationToken.generate(DeviceFacts("16", "Pixel", "Google", "board", 36, false, "bad"))
        }
    }
}
