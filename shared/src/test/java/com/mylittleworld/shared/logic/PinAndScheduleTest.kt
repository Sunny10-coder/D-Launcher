package com.mylittleworld.shared.logic

import com.mylittleworld.shared.model.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PinAndScheduleTest {

    @Test
    fun `pin is hashed with salt and verifies`() {
        val hash = PinPolicy.hash("1234")
        assertTrue(hash.startsWith("pbkdf2-sha256$"))
        assertTrue(PinPolicy.verify("1234", hash))
        assertFalse(PinPolicy.verify("4321", hash))
        assertFalse(PinPolicy.verify("12345", "not-a-hash"))
        assertFalse(PinPolicy.verify("1234", null))
    }

    @Test
    fun `same pin produces different hashes`() {
        assertTrue(PinPolicy.hash("9999") != PinPolicy.hash("9999"))
    }

    @Test
    fun `pin format is restricted to four to eight digits`() {
        assertTrue(PinPolicy.isValidPin("1234"))
        assertTrue(PinPolicy.isValidPin("12345678"))
        assertFalse(PinPolicy.isValidPin("123"))
        assertFalse(PinPolicy.isValidPin("123456789"))
        assertFalse(PinPolicy.isValidPin("12a4"))
        assertFalse(PinPolicy.isValidPin(""))
    }

    @Test
    fun `schedule window handles normal and midnight crossing`() {
        val normal = Schedule.parseWindow("08:00", "16:30")
        assertNotNull(normal)
        assertTrue(Schedule.isWithin(normal!!, Schedule.minutesOfDay(9, 0)))
        assertFalse(Schedule.isWithin(normal, Schedule.minutesOfDay(17, 0)))

        val night = Schedule.parseWindow("20:00", "07:00")
        assertNotNull(night)
        assertTrue(Schedule.isWithin(night!!, Schedule.minutesOfDay(23, 30)))
        assertTrue(Schedule.isWithin(night, Schedule.minutesOfDay(6, 59)))
        assertFalse(Schedule.isWithin(night, Schedule.minutesOfDay(12, 0)))
    }

    @Test
    fun `malformed schedule disables restriction`() {
        assertNull(Schedule.parseWindow("8am", "9am"))
        assertNull(Schedule.parseWindow("25:00", "09:00"))
        assertNull(Schedule.parseWindow("10:00", "10:00"))
    }

    @Test
    fun `location freshness never reports old fixes as live`() {
        val now = Instant.parse("2026-11-21T10:00:00Z")
        assertEquals(LocationFreshness.State.LIVE, LocationFreshness.state(now.minusSeconds(5), now))
        assertEquals(LocationFreshness.State.RECENT, LocationFreshness.state(now.minusSeconds(600), now))
        assertEquals(LocationFreshness.State.STALE, LocationFreshness.state(now.minusSeconds(7200), now))
        assertEquals(LocationFreshness.State.EXPIRED, LocationFreshness.state(now.minusSeconds(90_000), now))
        assertEquals(LocationFreshness.State.FUTURE, LocationFreshness.state(now.plusSeconds(600), now))
        assertEquals("just now", LocationFreshness.describe(now.minusSeconds(3), now))
        assertEquals("2 h ago", LocationFreshness.describe(now.minusSeconds(7200), now))
    }

    @Test
    fun `accuracy labels and mock detection`() {
        assertTrue(LocationFreshness.accuracyLabel(10.0).contains("very accurate"))
        assertTrue(LocationFreshness.accuracyLabel(100.0).contains("approximate"))
        val good = LocationSample(9.9, 76.3, 20.0, "2026-11-21T10:00:00Z")
        assertTrue(LocationFreshness.isTrustworthy(good))
        assertFalse(LocationFreshness.isTrustworthy(good.copy(isMock = true)))
        assertFalse(LocationFreshness.isTrustworthy(good.copy(lat = 200.0)))
    }

    @Test
    fun `pairing codes are digit normalised`() {
        assertEquals("123456", PairingCode.normalise("123 456"))
        assertEquals("123456", PairingCode.normalise("123-456"))
        assertNull(PairingCode.normalise("12345"))
        assertEquals("123 456", PairingCode.display("123456"))
    }
}
