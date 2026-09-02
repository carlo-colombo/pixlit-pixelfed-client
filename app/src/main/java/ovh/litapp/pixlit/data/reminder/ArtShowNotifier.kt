package ovh.litapp.pixlit.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ovh.litapp.pixlit.MainActivity
import ovh.litapp.pixlit.R
import javax.inject.Inject

class ArtShowNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    fun notify(theme: String?, saturday: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Art show reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val title = "#BlueSkyArtShow${theme?.let { ": $it" } ?: ""}"
        val intent = Intent(context, MainActivity::class.java).putExtra("prefill", true).putExtra("theme", theme)
        val pending = PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(if (saturday) 2 else 1,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title).setContentText(if (saturday) "Good morning, share your photo for this week's theme." else "It's time to share a photo for this week's theme.")
                    .setContentIntent(pending).setAutoCancel(true).build())
        }
    }
    companion object { const val CHANNEL = "art_show_reminders" }
}
