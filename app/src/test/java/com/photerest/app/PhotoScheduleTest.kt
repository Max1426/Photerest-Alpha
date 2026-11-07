package com.photerest.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PhotoScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.of(2026, 11, 2)
    private fun at(day: Int, hour: Int, minute: Int = 0) = monday.plusDays(day.toLong()).atTime(hour, minute).atZone(zone).toInstant()
    private fun interval() = DailyConfig(mode = DailyMode.INTERVAL, intervalMinutes = 30)
    private fun DailyConfig.day(index: Int, enabled: Boolean = true, vararg quiet: QuietTime) = copy(
        week = week.mapIndexed { at, previous -> if (at == index) DailyDay(enabled, quiet.toList()) else previous })
    private fun slot(at: Instant) = at.atZone(zone).let { DailySlot(it.toLocalDate(), it.hour * 60 + it.minute, at, DailyMode.INTERVAL) }

    @Test fun legacyDefaultsRetainFixedTimesAndAllDays() {
        val config = PhotoSchedule.normalize(DailyConfig())
        assertEquals(DailyMode.FIXED, config.mode)
        assertEquals(30, config.intervalMinutes)
        assertTrue(config.week.all { it.enabled && it.quiet.isEmpty() })
        assertEquals(at(0, 20), PhotoSchedule.first(config, at(0, 9), zone)!!.instant)
    }
    @Test fun minimumIsFifteenAndMalformedRangesAreRemoved() {
        val config = PhotoSchedule.normalize(interval().copy(intervalMinutes = 1).day(0, true, QuietTime(10, 10), QuietTime(-1, 60), QuietTime(60, 90)))
        assertEquals(15, config.intervalMinutes)
        assertEquals(listOf(QuietTime(60, 90)), config.week[0].quiet)
    }
    @Test fun firstEnabledIntervalWaitsWholePeriod() {
        assertEquals(at(0, 10, 30), PhotoSchedule.first(interval(), at(0, 10), zone)!!.instant)
    }
    @Test fun restoringFutureDoesNotPostponeCountdown() {
        assertEquals(at(0, 10, 30), PhotoSchedule.restore(interval(), at(0, 10, 30), at(0, 10, 20), zone)!!.instant)
    }
    @Test fun quietStartIsInclusiveAndEndExclusive() {
        val config = interval().day(0, true, QuietTime(720, 840))
        assertFalse(PhotoSchedule.allowed(config, at(0, 12), zone))
        assertTrue(PhotoSchedule.allowed(config, at(0, 14), zone))
        assertEquals(at(0, 14), PhotoSchedule.first(config, at(0, 12), zone)!!.instant)
    }
    @Test fun nextIntervalCrossingQuietResumesImmediately() {
        val config = interval().day(0, true, QuietTime(720, 840))
        assertEquals(at(0, 14), PhotoSchedule.first(config, at(0, 11, 45), zone)!!.instant)
        assertEquals(at(0, 14, 30), PhotoSchedule.following(config, slot(at(0, 14)), at(0, 14), zone)!!.instant)
    }
    @Test fun multipleOverlappingAndTouchingRangesMerge() {
        val config = interval().day(0, true, QuietTime(720, 800), QuietTime(780, 840), QuietTime(840, 900))
        assertEquals(at(0, 15), PhotoSchedule.first(config, at(0, 13), zone)!!.instant)
    }
    @Test fun overnightBelongsToStartingDay() {
        val config = interval().day(0, true, QuietTime(1380, 480))
        assertFalse(PhotoSchedule.allowed(config, at(1, 7, 59), zone))
        assertEquals(at(1, 8), PhotoSchedule.first(config, at(0, 23), zone)!!.instant)
    }
    @Test fun sundayOvernightWrapsIntoMonday() {
        val config = interval().day(6, true, QuietTime(1380, 480))
        assertFalse(PhotoSchedule.allowed(config, at(0, 7), zone))
        assertEquals(at(0, 8), PhotoSchedule.first(config, at(0, 7), zone)!!.instant)
    }
    @Test fun disabledDayTakesPriorityOverPreviousOvernight() {
        val config = interval().day(6, true, QuietTime(1380, 480)).day(0, false)
        assertEquals(at(1, 0), PhotoSchedule.first(config, at(0, 7), zone)!!.instant)
    }
    @Test fun disabledDaysSavedQuietRangesAreInactive() {
        val config = interval().day(0, false, QuietTime(1380, 480))
        assertEquals(at(1, 0), PhotoSchedule.first(config, at(0, 22), zone)!!.instant)
    }
    @Test fun allDaysDisabledHasNoNextAlarm() {
        val config = interval().copy(week = List(7) { DailyDay(false) })
        assertNull(PhotoSchedule.first(config, at(0, 10), zone))
        assertNull(PhotoSchedule.first(config.copy(mode = DailyMode.FIXED), at(0, 10), zone))
    }
    @Test fun quietCoveringEntireWeekHasNoNextAlarm() {
        val config = interval().copy(week = List(7) { DailyDay(quiet = listOf(QuietTime(0, 720), QuietTime(720, 0))) })
        assertNull(PhotoSchedule.first(config, at(0, 10), zone))
    }
    @Test fun fixedTimesSkipDisabledDaysAndQuiet() {
        val config = DailyConfig(times = listOf(540, 1200)).day(0, false).day(1, true, QuietTime(480, 600))
        assertEquals(at(1, 20), PhotoSchedule.first(config, at(0, 0), zone)!!.instant)
    }
    @Test fun fixedTimesDoNotSendAtQuietEndUnlessScheduled() {
        val config = DailyConfig(times = listOf(540, 1200)).day(0, true, QuietTime(480, 600))
        assertEquals(at(0, 20), PhotoSchedule.first(config, at(0, 9), zone)!!.instant)
    }
    @Test fun lateIntervalsCoalesceIntoOneLatestOccurrence() {
        val due = PhotoSchedule.due(interval(), slot(at(0, 9)), at(0, 10, 8), zone)!!
        assertEquals(at(0, 10), due.instant)
        assertEquals(at(0, 10, 30), PhotoSchedule.following(interval(), due, at(0, 10, 8), zone)!!.instant)
    }
    @Test fun oldOccurrenceCannotBeDeliveredAcrossQuietBoundary() {
        val config = interval().day(0, true, QuietTime(720, 840))
        assertFalse(PhotoSchedule.eligible(config, slot(at(0, 11, 45)), at(0, 14, 5), zone))
        assertEquals(at(0, 14), PhotoSchedule.due(config, slot(at(0, 11, 45)), at(0, 14, 5), zone)!!.instant)
        assertNull(PhotoSchedule.due(config, slot(at(0, 11, 45)), at(0, 13), zone))
    }
    @Test fun modeSwitchRejectsOldOccurrencesAndKeysStayCompatible() {
        val old = DailySlot(monday, 540, at(0, 9))
        assertEquals("2026-11-02:540", old.key)
        assertFalse(PhotoSchedule.eligible(interval(), old, at(0, 9), zone))
        assertTrue(slot(at(0, 9)).key.startsWith("interval:"))
    }
    @Test fun graceIsBoundedAndFutureOccurrencesAreRejected() {
        assertFalse(PhotoSchedule.eligible(interval(), slot(at(0, 9)), at(0, 9, 31), zone))
        assertFalse(PhotoSchedule.eligible(interval(), slot(at(0, 9)), at(0, 8, 59), zone))
        assertFalse(PhotoSchedule.eligible(interval().copy(intervalMinutes = 240), slot(at(0, 9)), at(0, 11, 1), zone))
    }
    @Test fun daylightSavingGapAndRepeatedHourUseRealInstants() {
        val berlin = ZoneId.of("Europe/Berlin")
        val config = interval().day(6, true, QuietTime(90, 180))
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), PhotoSchedule.first(config, Instant.parse("2026-03-29T00:15:00Z"), berlin)!!.instant)
        val repeated = Instant.parse("2026-10-25T00:45:00Z")
        assertEquals(repeated.plusSeconds(1800), PhotoSchedule.first(interval(), repeated, berlin)!!.instant)
        val autumn = interval().day(6, true, QuietTime(90, 150))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), PhotoSchedule.first(autumn, repeated, berlin)!!.instant)
    }
    @Test fun largeIntervalsStillResumeAtNextAllowedWindow() {
        assertEquals(at(0, 14), PhotoSchedule.first(interval().copy(intervalMinutes = Int.MAX_VALUE).day(0, true, QuietTime(720, 840)), at(0, 9), zone)!!.instant)
    }
}
