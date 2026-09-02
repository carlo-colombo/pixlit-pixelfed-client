package ovh.litapp.pixlit.data.reminder

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ovh.litapp.pixlit.data.repository.BlueskyArtShowRepository

@HiltWorker
class ArtShowReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: BlueskyArtShowRepository,
    private val notifier: ArtShowNotifier,
    private val scheduler: ReminderScheduler
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val theme = runCatching { repository.fetchTheme() }.getOrNull()
        notifier.notify(theme, inputData.getInt(DAY_KEY, 5) == 6)
        scheduler.scheduleAll()
        return Result.success()
    }
    companion object { const val DAY_KEY = "day" }
}
