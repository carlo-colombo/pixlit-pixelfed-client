package ovh.litapp.pixlit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import ovh.litapp.pixlit.data.reminder.ArtShowNotifier
import javax.inject.Inject

@HiltAndroidApp
class PixlitApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Register the reminder channel once per process. Importance is fixed the first time a
        // channel id is created and later changes are ignored, so this is the single definition.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                ArtShowNotifier.CHANNEL,
                "Art show reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
