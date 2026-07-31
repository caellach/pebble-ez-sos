package com.ezsos.companion.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosAuthTokenTest {
    @Test
    fun roundTrip_encryptDecrypt() {
        val now = 1_735_689_600L
        val line = SosAuthToken.buildLine(now)
        assertTrue(line.startsWith("ez."))
        val token = line.removePrefix("ez.")
        assertEquals(now, SosAuthToken.decryptEpochSeconds(token))
    }

    @Test
    fun extractToken_lastNonEmptyLine() {
        val body = "EZ SOS: help\nLat: 1, Lon: 2\nhttps://maps.google.com/?q=1,2\nez.abc123\n"
        assertEquals("abc123", SosAuthToken.extractTokenFromBody(body))
    }

    @Test
    fun extractToken_missing_returnsNull() {
        assertNull(SosAuthToken.extractTokenFromBody("EZ SOS: help\nLat: 1"))
    }

    @Test
    fun isFresh_insideWindow() {
        assertTrue(SosAuthToken.isFresh(1000, windowMinutes = 15, nowEpochSeconds = 1000 + 14 * 60))
        assertTrue(SosAuthToken.isFresh(1000, windowMinutes = 15, nowEpochSeconds = 1000 - 14 * 60))
    }

    @Test
    fun isFresh_outsideWindow() {
        assertFalse(SosAuthToken.isFresh(1000, windowMinutes = 15, nowEpochSeconds = 1000 + 16 * 60))
    }

    @Test
    fun validateBody_happyPath() {
        val now = 1_700_000_000L
        val body = "EZ SOS: I need help.\nLat: 0.0, Lon: 0.0\nhttps://maps.google.com/?q=0.0,0.0\n" +
            SosAuthToken.buildLine(now)
        assertTrue(SosAuthToken.validateBody(body, windowMinutes = 15, nowEpochSeconds = now))
    }

    @Test
    fun validateBody_badToken_false() {
        val body = "EZ SOS: x\nez.not-valid-base64!!!"
        assertFalse(SosAuthToken.validateBody(body, windowMinutes = 15, nowEpochSeconds = 1L))
    }

    @Test
    fun decrypt_garbage_returnsNull() {
        assertNull(SosAuthToken.decryptEpochSeconds("not-a-token"))
    }
}
