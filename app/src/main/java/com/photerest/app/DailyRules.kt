package com.photerest.app

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

enum class DailySource(val label: String) { ALL("全部已授权照片"), ALBUMS("指定相册"), FAVORITES("我收藏的图片") }
enum class DailyMode(val label: String) { FIXED("固定时间"), INTERVAL("间隔推荐") }
data class QuietTime(val start: Int, val end: Int)
data class DailyDay(val enabled: Boolean = true, val quiet: List<QuietTime> = emptyList())

data class DailyConfig(
    val enabled: Boolean = false,
    val source: DailySource = DailySource.ALL,
    val albums: Set<String> = emptySet(),
    val times: List<Int> = listOf(20 * 60),
    val lockscreenPreview: Boolean = true,
    val revision: Long = 0,
    val scope: Long = 0,
    val mode: DailyMode = DailyMode.FIXED,
    val intervalMinutes: Int = 30,
    val week: List<DailyDay> = List(7) { DailyDay() },
)

data class DailySlot(val day: LocalDate, val minute: Int, val instant: Instant, val mode: DailyMode = DailyMode.FIXED) {
    val key: String get() = if (mode == DailyMode.FIXED) "$day:$minute" else "interval:${instant.toEpochMilli()}"
}

object DailyRules {
    fun timeLabel(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
    fun normalize(times: List<Int>): List<Int> = times.filter { it in 0..1439 }.distinct().sorted().ifEmpty { listOf(1200) }
    internal fun slots(day: LocalDate, times: List<Int>, zone: ZoneId) = normalize(times).map {
        DailySlot(day, it, day.atStartOfDay().plusMinutes(it.toLong()).atZone(zone).toInstant())
    }
    fun next(times: List<Int>, now: Instant, zone: ZoneId): DailySlot {
        val day = now.atZone(zone).toLocalDate()
        return (slots(day, times, zone) + slots(day.plusDays(1), times, zone)).filter { it.instant > now }
            .minWith(compareBy<DailySlot> { it.instant }.thenByDescending { it.minute })
    }
    fun eligible(slot: DailySlot, times: List<Int>, now: Instant, zone: ZoneId): Boolean {
        if (slot.minute !in normalize(times) || slot.day != now.atZone(zone).toLocalDate()) return false
        if (now < slot.instant || Duration.between(slot.instant, now) > Duration.ofHours(2)) return false
        // At a daylight-saving gap, two wall-clock times can resolve to the same instant.
        val latest = latestDue(times, now, zone)
        return latest?.key == slot.key && latest.instant == slot.instant
    }
    fun latestDue(times: List<Int>, now: Instant, zone: ZoneId): DailySlot? =
        slots(now.atZone(zone).toLocalDate(), times, zone).filter { it.instant <= now }
            .maxWithOrNull(compareBy<DailySlot> { it.instant }.thenBy { it.minute })
    fun order(ids: List<String>, seen: Set<String>, last: String?, random: Random = Random.Default): List<String> {
        val unique = ids.distinct()
        val fresh = unique.filter { it !in seen }.shuffled(random)
        val old = unique.filter { it in seen }.shuffled(random)
        // Previously seen photos are fallbacks only if all fresh sources are now unreadable.
        val ordered = fresh + old
        return if (ordered.size > 1 && ordered.firstOrNull() == last) ordered.filter { it != last } + listOfNotNull(last) else ordered
    }
}
