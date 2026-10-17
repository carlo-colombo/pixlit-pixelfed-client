package ovh.litapp.pixlit.data.reminder

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ReminderPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun scheduleAll() {
        scope.launch { scheduleAllNow() }
    }

    fun rescheduleAll() {
        scope.launch { rescheduleAllNow() }
    }

    /**
     * Reads the authoritative stored times (suspending) and (re)schedules both reminders. Best-effort:
     * a stale or corrupt stored time must not crash the caller. Each day is guarded separately so a
     * bad value for one cannot cancel the other reminder.
     */
    suspend fun scheduleAllNow() {
        val times = preferences.current()
        runCatching { schedule(DayOfWeek.FRIDAY, times.friday) }
            .onFailure { Log.w(TAG, "Failed to schedule the Friday art-show reminder", it) }
        runCatching { schedule(DayOfWeek.SATURDAY, times.saturday) }
            .onFailure { Log.w(TAG, "Failed to schedule the Saturday art-show reminder", it) }
    }

    suspend fun rescheduleAllNow() {
        cancel(DayOfWeek.FRIDAY)
        cancel(DayOfWeek.SATURDAY)
        scheduleAllNow()
    }

    private fun schedule(day: DayOfWeek, time: LocalTime) {
        val delay = nextRunDelay(day, time)
        val intent = Intent(context, ArtShowReminderReceiver::class.java)
            .putExtra(ArtShowReminderReceiver.DAY_KEY, day.value)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            day.value,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delay.toMillis(),
            pendingIntent
        )
    }

    private fun cancel(day: DayOfWeek) {
        val intent = Intent(context, ArtShowReminderReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            day.value,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    companion object {
        private const val TAG = "ReminderScheduler"
    }
}

fun nextRunDelay(day: DayOfWeek, time: LocalTime, now: ZonedDateTime = ZonedDateTime.now()): Duration {
    var next = now.with(day).with(time).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusWeeks(1)
    return Duration.between(now, next)
}
