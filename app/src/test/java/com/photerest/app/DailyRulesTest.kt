package com.photerest.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class DailyRulesTest {
    @Test fun identityEncodingPreservesExistingRecommendationHistory() {
        val photo = Photo("content://media/external/images/media/123", "风景🌅.jpg", "camera", "image/jpeg", 900, 1200, 1000, 0, 1793500000)
        val old = java.security.MessageDigest.getInstance("SHA-256").digest("${photo.uri}\n${photo.added}\n${photo.name}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(old, photo.dailyIdentity())
        assertEquals(64, photo.dailyIdentity().length)
    }
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 1)
    private fun time(hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant()
    private fun slot(hour: Int) = DailySlot(day, hour * 60, time(hour))
    @Test fun defaultsAreOptInAndOnePhotoAtTwenty() {
        val c = DailyConfig()
        assertFalse(c.enabled); assertTrue(c.lockscreenPreview)
        assertEquals(listOf(1200), c.times); assertEquals(DailySource.ALL, c.source)
    }
    @Test fun timesAreValidUniqueSortedAndNeverEmpty() {
        assertEquals(listOf(0, 540, 1200), DailyRules.normalize(listOf(1200, -1, 540, 0, 1200, 1440)))
        assertEquals(listOf(1200), DailyRules.normalize(emptyList()))
        assertEquals("09:05", DailyRules.timeLabel(545))
    }
    @Test fun nextSlotUsesLocalDayAndRollsOver() {
        assertEquals(1200, DailyRules.next(listOf(540, 1200), time(10), zone).minute)
        val next = DailyRules.next(listOf(540, 1200), time(20), zone)
        assertEquals(day.plusDays(1), next.day); assertEquals(540, next.minute)
    }
    @Test fun latestDueSlotWinsInsteadOfBursting() {
        assertFalse(DailyRules.eligible(slot(9), listOf(540, 600), time(10, 5), zone))
        assertTrue(DailyRules.eligible(slot(10), listOf(540, 600), time(10, 5), zone))
    }
    @Test fun graceIsTwoHoursAndNeverCrossesDay() {
        assertTrue(DailyRules.eligible(slot(9), listOf(540), time(11), zone))
        assertFalse(DailyRules.eligible(slot(9), listOf(540), time(11, 1), zone))
        assertFalse(DailyRules.eligible(slot(23), listOf(1380), time(23).plusSeconds(7200), zone))
    }
    @Test fun earlyRemovedOrShiftedSlotsAreRejected() {
        assertFalse(DailyRules.eligible(slot(9), listOf(540), time(8), zone))
        assertFalse(DailyRules.eligible(slot(9), listOf(600), time(10), zone))
        assertFalse(DailyRules.eligible(slot(9), listOf(540), time(9), ZoneId.of("Europe/London")))
    }
    @Test fun dstSpringGapHasOneConsistentWinner() {
        val berlin = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-03-29T00:30:00Z")
        val next = DailyRules.next(listOf(150, 210), now, berlin)
        assertEquals(210, next.minute)
        assertTrue(DailyRules.eligible(next, listOf(150, 210), next.instant.plusSeconds(30), berlin))
    }
    @Test fun dstFallRepeatedHourIsNotScheduledTwice() {
        val berlin = ZoneId.of("Europe/Berlin")
        val next = DailyRules.next(listOf(150), Instant.parse("2026-10-25T00:31:00Z"), berlin)
        assertEquals(LocalDate.of(2026, 10, 26), next.day)
    }
    @Test fun unseenSourcesPrecedeFallbacks() {
        val order = DailyRules.order(listOf("a", "b", "c", "a"), setOf("a"), "a", Random(1))
        assertEquals(setOf("b", "c"), order.take(2).toSet()); assertEquals("a", order.last())
    }
    @Test fun newRoundAvoidsImmediateRepeat() {
        repeat(20) {
            val order = DailyRules.order(listOf("a", "b"), setOf("a", "b"), "a", Random(it))
            assertEquals("b", order.first()); assertEquals(2, order.size)
        }
        assertEquals(listOf("a"), DailyRules.order(listOf("a"), setOf("a"), "a"))
        assertTrue(DailyRules.order(emptyList(), emptySet(), null).isEmpty())
    }
}
