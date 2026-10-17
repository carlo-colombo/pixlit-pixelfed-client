package ovh.litapp.pixlit.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAlarmManager
import ovh.litapp.pixlit.data.repository.BlueskyArtShowRepository

@RunWith(AndroidJUnit4::class)
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var preferences: ReminderPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pixlit_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = ReminderPreferences(context)
    }

    @Test fun `next run rolls to next week after scheduled time`() {
        val now = ZonedDateTime.of(2026, 9, 4, 21, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals(Duration.ofDays(6).plusHours(23), nextRunDelay(DayOfWeek.FRIDAY, LocalTime.of(20, 0), now))
    }

    @Test fun `next run is same day before scheduled time`() {
        val now = ZonedDateTime.of(2026, 9, 4, 19, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals(Duration.ofHours(1), nextRunDelay(DayOfWeek.FRIDAY, LocalTime.of(20, 0), now))
    }

    @Test fun `next run crosses spring forward without an hour of drift`() {
        // 2026-03-08 02:00 EST springs forward to 03:00 EDT, so Sunday 03:30 local is only
        // 1h30m of real time after Sunday 01:00 local, not the 2h30m a wall-clock subtraction gives.
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, zone)
        assertEquals(
            Duration.ofHours(1).plusMinutes(30),
            nextRunDelay(DayOfWeek.SUNDAY, LocalTime.of(3, 30), now)
        )
    }

    @Test fun `cancel resolves the pending intent created by schedule`() {
        val scheduler = ReminderScheduler(context, preferences)

        scheduler.scheduleAll()
        val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(2, alarms.scheduledAlarms.size)

        val fridayIntent = scheduledIntent(alarms, DayOfWeek.FRIDAY.value)
        val friday = Shadows.shadowOf(fridayIntent)
        assertEquals(DayOfWeek.FRIDAY.value, friday.requestCode)
        assertTrue(friday.isImmutable)

        // cancel() looks the alarm up by request code with FLAG_IMMUTABLE / FLAG_NO_CREATE; extras are
        // ignored by PendingIntent matching, so it must resolve to the same instance schedule() created.
        val resolved = PendingIntent.getBroadcast(
            context,
            DayOfWeek.FRIDAY.value,
            Intent(context, ArtShowReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(resolved)
        assertEquals(fridayIntent, resolved)

        // A matching cancel removes both alarms before they are re-added; a mismatch would leave four.
        scheduler.rescheduleAll()
        assertEquals(2, alarms.scheduledAlarms.size)
    }

    @Test fun `preferences default to Friday evening and Saturday morning`() {
        assertEquals(LocalTime.of(20, 0), preferences.friday)
        assertEquals(LocalTime.of(8, 0), preferences.saturday)
    }

    @Test fun `out of range stored times are clamped on read`() {
        context.getSharedPreferences("pixlit_reminders", Context.MODE_PRIVATE).edit()
            .putInt("friday_hour", 99).putInt("friday_minute", 99)
            .putInt("saturday_hour", -1).putInt("saturday_minute", -1)
            .commit()

        assertEquals(LocalTime.of(23, 59), preferences.friday)
        assertEquals(LocalTime.of(0, 0), preferences.saturday)
    }

    @Test fun `worker posts an untitled fallback notification when the theme fetch fails`() = runTest {
        val repository = mockk<BlueskyArtShowRepository>()
        coEvery { repository.fetchTheme() } throws IOException("offline")
        val notifier = mockk<ArtShowNotifier>(relaxed = true)

        val worker = TestListenableWorkerBuilder.from(context, ArtShowReminderWorker::class.java)
            .setWorkerFactory(ReminderWorkerFactory(repository, notifier))
            .setInputData(workDataOf(ArtShowReminderWorker.DAY_KEY to DayOfWeek.FRIDAY.value))
            .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        verify { notifier.notify(isNull(), false) }
    }

    private fun scheduledIntent(alarms: ShadowAlarmManager, requestCode: Int): PendingIntent =
        alarms.scheduledAlarms.mapNotNull { it.operation }
            .first { Shadows.shadowOf(it).requestCode == requestCode }

    private class ReminderWorkerFactory(
        private val repository: BlueskyArtShowRepository,
        private val notifier: ArtShowNotifier
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker? =
            if (workerClassName == ArtShowReminderWorker::class.java.name) {
                ArtShowReminderWorker(appContext, workerParameters, repository, notifier)
            } else {
                null
            }
    }
}
