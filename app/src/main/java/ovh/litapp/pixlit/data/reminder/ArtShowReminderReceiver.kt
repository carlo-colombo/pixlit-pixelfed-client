package ovh.litapp.pixlit.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ArtShowReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var notifier: ArtShowNotifier
    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val day = intent.getIntExtra(DAY_KEY, 5)
        val saturday = day == 6

        // Post immediately; theme lookup must not prevent the reminder from appearing.
        notifier.notify(null, saturday)
        scheduler.scheduleAll()

        val request = OneTimeWorkRequestBuilder<ArtShowReminderWorker>()
            .setInputData(workDataOf(ArtShowReminderWorker.DAY_KEY to day))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "art_show_theme_$day",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        const val DAY_KEY = "day"
    }
}
