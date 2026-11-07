package com.photerest.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Dedicated emulator only. External script kills this process before the real alarm is due. */
@PermissionScenario
class DailyAlarmDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = DailyRecommendations.get(context)
    private val prefs get() = context.getSharedPreferences("daily_recommendations", Context.MODE_PRIVATE)
    private fun alarmIntent(slot: DailySlot, revision: Long) = Intent(context, DailyAlarmReceiver::class.java).setAction(DailyAlarms.ACTION)
        .putExtra("day", slot.day.toString()).putExtra("minute", slot.minute)
        .putExtra("instant", slot.instant.toEpochMilli()).putExtra("revision", revision).putExtra("mode", slot.mode.name)

    @Test fun armNextMinute() {
        assertTrue("Grant photo access and seed a test image first", Gallery.scan(context).second.isNotEmpty())
        assertTrue(repository.notificationsAllowed())
        assertTrue("Grant alarms and reminders before exact delivery test", repository.exactAlarmsAllowed())
        val lead = InstrumentationRegistry.getArguments().getString("alarmLeadSeconds")?.toLongOrNull() ?: 120L
        val local = Instant.now().plusSeconds(lead.coerceIn(120, 300)).atZone(ZoneId.systemDefault()).withSecond(0).withNano(0)
        val minute = local.hour * 60 + local.minute
        context.getSystemService(NotificationManager::class.java).cancelAll()
        // Do not erase any other day's delivery history.
        repository.history.writableDatabase.delete("deliveries", "slot=?", arrayOf("${local.toLocalDate()}:$minute"))
        runBlocking { repository.update { it.copy(enabled = true, source = DailySource.ALL, albums = emptySet(), times = listOf(minute), lockscreenPreview = true, mode = DailyMode.FIXED, week = DailyConfig().week) } }
        assertTrue(prefs.getBoolean("alarm_exact", false))
        assertEquals(local.toInstant().toEpochMilli(), prefs.getLong("alarm_at", 0))
        println("DAILY_ALARM_TARGET=${local.toInstant().toEpochMilli()}")
        println("DAILY_ALARM_SLOT=${local.toLocalDate()}:$minute")
        java.io.File(context.filesDir, "daily-alarm-test-target.json").writeText(org.json.JSONObject()
            .put("target", local.toInstant().toEpochMilli()).put("slot", "${local.toLocalDate()}:$minute").toString())
    }

    @Test fun armIntervalResume() {
        assertTrue(Gallery.scan(context).second.isNotEmpty())
        assertTrue(repository.notificationsAllowed() && repository.exactAlarmsAllowed())
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        val lead = InstrumentationRegistry.getArguments().getString("alarmLeadSeconds")?.toLongOrNull() ?: 120L
        val end = now.plusSeconds(lead.coerceIn(120, 300)).withSecond(0).withNano(0)
        val start = now.minusMinutes(1)
        val week = List(7) { index -> if (index == start.dayOfWeek.value - 1) {
            DailyDay(quiet = listOf(QuietTime(start.hour * 60 + start.minute, end.hour * 60 + end.minute)))
        } else DailyDay() }
        val target = end.toInstant().toEpochMilli()
        val key = "interval:$target"
        context.getSystemService(NotificationManager::class.java).cancelAll()
        repository.history.writableDatabase.delete("deliveries", "slot=?", arrayOf(key))
        runBlocking { repository.update { it.copy(enabled = true, source = DailySource.ALL, albums = emptySet(),
            mode = DailyMode.INTERVAL, intervalMinutes = 15, week = week, lockscreenPreview = true) } }
        assertEquals(target, prefs.getLong("alarm_at", 0))
        assertTrue(prefs.getBoolean("alarm_exact", false))
        java.io.File(context.filesDir, "daily-alarm-test-target.json").writeText(org.json.JSONObject().put("target", target).put("slot", key).toString())
    }

    @Test fun intervalPlanSurvivesReconcileAndAllDaysOffCancels() {
        try {
            runBlocking { repository.update { it.copy(enabled = true, mode = DailyMode.INTERVAL, intervalMinutes = 30, week = DailyConfig().week) } }
            val target = prefs.getLong("alarm_at", 0)
            assertTrue(target > System.currentTimeMillis())
            Thread.sleep(75)
            runBlocking { repository.reconcile() }
            assertEquals(target, prefs.getLong("alarm_at", 0))
            runBlocking { repository.update { it.copy(week = List(7) { DailyDay(false) }) } }
            assertNull(repository.planned.value)
            assertNull(DailyAlarms(context).pending())
        } finally { runBlocking { repository.update { it.copy(enabled = false, mode = DailyMode.FIXED, week = DailyConfig().week) } } }
    }

    @Test fun schedulingAndCancellation() {
        try {
            runBlocking { repository.update { it.copy(enabled = true, mode = DailyMode.FIXED, week = DailyConfig().week) } }
            assertNotNull(DailyAlarms(context).pending())
            assertTrue(prefs.getLong("alarm_at", 0) > System.currentTimeMillis())
            assertEquals(repository.exactAlarmsAllowed(), prefs.getBoolean("alarm_exact", false))
            runBlocking { repository.update { it.copy(enabled = false) } }
            assertNull(DailyAlarms(context).pending())
            assertFalse(prefs.contains("alarm_at"))
        } finally { runBlocking { repository.update { it.copy(enabled = false) } } }
    }

    @Test fun staleAlarmCannotDeliverOrReplaceNewSchedule() {
        try {
            runBlocking { repository.update { it.copy(enabled = true, times = listOf(540), mode = DailyMode.FIXED, week = DailyConfig().week) } }
            val old = repository.state.value.revision
            val slot = DailyRules.next(listOf(540), Instant.now(), ZoneId.systemDefault())
            runBlocking { repository.update { it.copy(times = listOf(600)) } }
            val expected = prefs.getLong("alarm_at", 0)
            val seen = repository.history.seen()
            runBlocking { repository.runAlarm(alarmIntent(slot, old)) }
            assertEquals(expected, prefs.getLong("alarm_at", 0))
            assertEquals(seen, repository.history.seen())
        } finally { runBlocking { repository.update { it.copy(enabled = false) } } }
    }

    @Test fun emptySourceStillSchedulesTomorrow() {
        try {
            val local = Instant.now().atZone(ZoneId.systemDefault()).withSecond(0).withNano(0)
            val minute = local.hour * 60 + local.minute
            val slot = DailySlot(local.toLocalDate(), minute, local.toInstant())
            repository.history.writableDatabase.delete("deliveries", "slot=?", arrayOf(slot.key))
            runBlocking { repository.update { it.copy(enabled = true, source = DailySource.ALBUMS, albums = setOf("alarm-qa-empty"), times = listOf(minute), mode = DailyMode.FIXED, week = DailyConfig().week) } }
            runBlocking { repository.runAlarm(alarmIntent(slot, repository.state.value.revision)) }
            assertEquals("当前推荐范围没有可访问照片", repository.status.value)
            assertTrue(prefs.getLong("alarm_at", 0) > System.currentTimeMillis())
        } finally { runBlocking { repository.update { it.copy(enabled = false) } } }
    }

    @Test fun disableForExternalTestCleanup() { runBlocking { repository.update { it.copy(enabled = false) } } }
}
