package com.photerest.app

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

/** Pure scheduling rules. Week indices use Monday = 0; all windows are [start, end). */
object PhotoSchedule {
    private data class Minutes(val start: Int, val end: Int)
    private data class Window(val start: Instant, val end: Instant)

    fun normalize(config: DailyConfig): DailyConfig = config.copy(
        times = DailyRules.normalize(config.times),
        intervalMinutes = config.intervalMinutes.coerceAtLeast(15),
        week = List(7) { index ->
            val day = config.week.getOrElse(index) { DailyDay() }
            day.copy(quiet = day.quiet.filter { it.start in 0..1439 && it.end in 0..1439 && it.start != it.end }
                .distinct().sortedBy { it.start })
        },
    )

    private fun openMinutes(config: DailyConfig): List<Minutes> {
        val blocked = mutableListOf<Minutes>()
        config.week.forEachIndexed { index, day ->
            val midnight = index * 1440
            if (!day.enabled) blocked.add(Minutes(midnight, midnight + 1440))
            else day.quiet.forEach { quiet ->
                val start = midnight + quiet.start
                val end = midnight + quiet.end + if (quiet.end < quiet.start) 1440 else 0
                blocked.add(Minutes(start, minOf(end, 10080)))
                if (end > 10080) blocked.add(Minutes(0, end - 10080))
            }
        }
        val result = mutableListOf<Minutes>()
        var cursor = 0
        blocked.sortedBy { it.start }.forEach {
            if (it.start > cursor) result.add(Minutes(cursor, it.start))
            cursor = maxOf(cursor, it.end)
        }
        if (cursor < 10080) result.add(Minutes(cursor, 10080))
        return result
    }

    private fun boundary(day: LocalDate, minute: Int, zone: ZoneId, opening: Boolean): Instant {
        val local = day.atStartOfDay().plusMinutes(minute.toLong())
        val transition = zone.rules.getTransition(local)
        // A quiet boundary in a skipped hour begins at the first existing local instant.
        if (transition?.isGap == true) return transition.instant
        val time = local.atZone(zone)
        // Keep quiet through both occurrences of an ambiguous quiet-end time.
        return (if (opening) time.withLaterOffsetAtOverlap() else time).toInstant()
    }

    private fun windows(config: DailyConfig, around: Instant, zone: ZoneId): List<Window> {
        val open = openMinutes(config)
        if (open == listOf(Minutes(0, 10080))) return listOf(Window(Instant.MIN, Instant.MAX))
        val monday = around.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val result = mutableListOf<Window>()
        for (offset in -1L..2L) {
            val day = monday.plusWeeks(offset)
            open.forEach { period ->
                val start = boundary(day, period.start, zone, opening = true)
                val end = boundary(day, period.end, zone, opening = false)
                if (start < end) {
                    val last = result.lastOrNull()
                    if (last != null && start <= last.end) result[result.lastIndex] = Window(last.start, maxOf(last.end, end))
                    else result.add(Window(start, end))
                }
            }
        }
        return result
    }

    fun allowed(config: DailyConfig, at: Instant, zone: ZoneId): Boolean =
        windows(config, at, zone).any { at >= it.start && at < it.end }

    private fun sameWindow(config: DailyConfig, start: Instant, end: Instant, zone: ZoneId): Boolean =
        windows(config, end, zone).any { start >= it.start && end < it.end }

    private fun intervalSlot(at: Instant, zone: ZoneId): DailySlot {
        val local = at.atZone(zone)
        return DailySlot(local.toLocalDate(), local.hour * 60 + local.minute, at, DailyMode.INTERVAL)
    }

    private fun afterInterval(config: DailyConfig, after: Instant, zone: ZoneId): DailySlot? {
        val periods = windows(config, after, zone)
        val current = periods.firstOrNull { after >= it.start && after < it.end }
        if (current == null) return periods.firstOrNull { it.start > after }?.let { intervalSlot(it.start, zone) }
        val candidate = after.plusSeconds(config.intervalMinutes.toLong() * 60)
        if (candidate < current.end) return intervalSlot(candidate, zone)
        return periods.firstOrNull { it.start >= current.end }?.let { intervalSlot(it.start, zone) }
    }

    private fun fixedSlots(config: DailyConfig, day: LocalDate, zone: ZoneId): List<DailySlot> {
        val open = windows(config, day.atStartOfDay(zone).toInstant(), zone)
        return DailyRules.slots(day, config.times, zone).filter { slot -> open.any { slot.instant >= it.start && slot.instant < it.end } }
    }

    fun first(config: DailyConfig, now: Instant, zone: ZoneId): DailySlot? {
        if (config.mode == DailyMode.INTERVAL) return afterInterval(config, now, zone)
        val day = now.atZone(zone).toLocalDate()
        return (0L..8L).firstNotNullOfOrNull { offset -> fixedSlots(config, day.plusDays(offset), zone)
            .filter { it.instant > now }.minWithOrNull(compareBy<DailySlot> { it.instant }.thenByDescending { it.minute }) }
    }

    /** Coalesce missed intervals in the current allowed window, without replaying a backlog. */
    fun due(config: DailyConfig, scheduled: DailySlot, now: Instant, zone: ZoneId): DailySlot? {
        if (scheduled.mode != config.mode || now < scheduled.instant) return null
        if (config.mode == DailyMode.FIXED) {
            if (scheduled.day != now.atZone(zone).toLocalDate()) return null
            return fixedSlots(config, scheduled.day, zone).filter { it.instant in scheduled.instant..now }
                .maxWithOrNull(compareBy<DailySlot> { it.instant }.thenBy { it.minute })
        }
        val current = windows(config, now, zone).firstOrNull { now >= it.start && now < it.end } ?: return null
        val base = maxOf(scheduled.instant, current.start)
        val seconds = config.intervalMinutes.toLong() * 60
        val count = Duration.between(base, now).seconds / seconds
        return intervalSlot(base.plusSeconds(count * seconds), zone)
    }

    fun eligible(config: DailyConfig, slot: DailySlot, now: Instant, zone: ZoneId): Boolean {
        if (slot.mode != config.mode || now < slot.instant || !sameWindow(config, slot.instant, now, zone)) return false
        val grace = if (config.mode == DailyMode.INTERVAL) minOf(config.intervalMinutes.toLong() * 60, 7200) else 7200
        if (Duration.between(slot.instant, now) > Duration.ofSeconds(grace)) return false
        if (config.mode == DailyMode.INTERVAL) return true
        return slot.day == now.atZone(zone).toLocalDate() &&
            fixedSlots(config, slot.day, zone).filter { it.instant <= now }
                .maxWithOrNull(compareBy<DailySlot> { it.instant }.thenBy { it.minute }) == slot
    }

    fun restore(config: DailyConfig, saved: Instant?, now: Instant, zone: ZoneId): DailySlot? {
        if (config.mode == DailyMode.FIXED || saved == null) return first(config, now, zone)
        if (saved > now && allowed(config, saved, zone)) return intervalSlot(saved, zone)
        return due(config, intervalSlot(saved, zone), now, zone) ?: first(config, now, zone)
    }

    fun following(config: DailyConfig, handled: DailySlot, now: Instant, zone: ZoneId): DailySlot? {
        if (config.mode == DailyMode.FIXED) return first(config, maxOf(handled.instant, now), zone)
        val latest = due(config, handled, now, zone)
        return afterInterval(config, latest?.instant ?: maxOf(now, handled.instant), zone)
    }
}
