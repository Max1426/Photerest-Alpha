package com.photerest.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.*

/** One persisted system wakeup alarm, independent of Activity and WorkManager job windows. */
internal class DailyAlarms(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    private fun intent() = Intent(context, DailyAlarmReceiver::class.java).setAction(ACTION)
    internal fun pending(flags: Int = PendingIntent.FLAG_NO_CREATE): PendingIntent? =
        PendingIntent.getBroadcast(context, 7301, intent(), flags or PendingIntent.FLAG_IMMUTABLE)

    fun cancel() {
        pending()?.let { manager.cancel(it); it.cancel() }
        context.getSharedPreferences("daily_recommendations", Context.MODE_PRIVATE).edit().remove("alarm_at").remove("alarm_exact").apply()
    }

    fun schedule(slot: DailySlot, revision: Long) {
        val operation = PendingIntent.getBroadcast(context, 7301, intent()
            .putExtra("day", slot.day.toString()).putExtra("minute", slot.minute)
            .putExtra("instant", slot.instant.toEpochMilli()).putExtra("revision", revision).putExtra("mode", slot.mode.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        var exact = exactAllowed()
        if (exact) {
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.instant.toEpochMilli(), operation) }
            catch (_: SecurityException) { exact = false } // permission changed between check and call
        }
        if (!exact) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.instant.toEpochMilli(), operation)
        context.getSharedPreferences("daily_recommendations", Context.MODE_PRIVATE).edit()
            .putLong("alarm_at", slot.instant.toEpochMilli()).putBoolean("alarm_exact", exact).apply()
        android.util.Log.i("DailyPhotos", "Armed ${slot.key} at=${slot.instant} exact=$exact revision=$revision")
    }

    companion object { const val ACTION = "com.photerest.app.DAILY_ALARM" }
}

class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyAlarms.ACTION) return
        val pending = goAsync()
        // AlarmManager's wake lock ends when onReceive returns, not when goAsync finishes.
        // Keep only this short local read/post awake; no permanent service or polling loop.
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Photerest:daily-photo").apply { acquire(10_000) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { DailyRecommendations.get(context).runAlarm(intent) } }
            catch (e: Exception) { android.util.Log.w("DailyPhotos", "Timed photo could not finish", e) }
            finally {
                if (wake.isHeld) wake.release()
                pending.finish()
            }
        }
    }
}
