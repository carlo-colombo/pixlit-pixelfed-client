package ovh.litapp.pixlit.data.reminder

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ReminderPreferences
) {
    fun scheduleAll() {
        schedule("art_show_friday", DayOfWeek.FRIDAY, preferences.friday)
        schedule("art_show_saturday", DayOfWeek.SATURDAY, preferences.saturday)
    }

    fun rescheduleAll() {
        WorkManager.getInstance(context).cancelUniqueWork("art_show_friday")
        WorkManager.getInstance(context).cancelUniqueWork("art_show_saturday")
        scheduleAll()
    }

    private fun schedule(name: String, day: DayOfWeek, time: LocalTime) {
        val delay = nextRunDelay(day, time)
        val request = OneTimeWorkRequestBuilder<ArtShowReminderWorker>()
            .setInputData(workDataOf(ArtShowReminderWorker.DAY_KEY to day.value))
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }
}

fun nextRunDelay(day: DayOfWeek, time: LocalTime, now: ZonedDateTime = ZonedDateTime.now()): Duration {
    var next = now.with(day).with(time).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusWeeks(1)
    return Duration.between(now, next)
}
