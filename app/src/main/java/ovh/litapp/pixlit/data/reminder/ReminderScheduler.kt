package ovh.litapp.pixlit.data.reminder

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ReminderPreferences
) {
    fun scheduleAll() {
        schedule(DayOfWeek.FRIDAY, preferences.friday)
        schedule(DayOfWeek.SATURDAY, preferences.saturday)
    }

    fun rescheduleAll() {
        cancel(DayOfWeek.FRIDAY)
        cancel(DayOfWeek.SATURDAY)
        scheduleAll()
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
}

fun nextRunDelay(day: DayOfWeek, time: LocalTime, now: ZonedDateTime = ZonedDateTime.now()): Duration {
    var next = now.with(day).with(time).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusWeeks(1)
    return Duration.between(now, next)
}
