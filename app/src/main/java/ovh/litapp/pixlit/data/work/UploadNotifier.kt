package ovh.litapp.pixlit.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import ovh.litapp.pixlit.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The foreground notification of a running upload (UP-05). WorkManager promotes the worker to a
 * foreground service for as long as it holds this notification, which is what lets the upload
 * survive the app leaving the foreground.
 */
@Singleton
class UploadNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * The upload's ongoing notification. The channel is created here rather than in
     * `PixlitApplication.onCreate` because this class is the only writer: creating a channel is
     * idempotent, and its importance is fixed by the first creation.
     */
    fun foregroundInfo(
        uploadedCount: Int,
        totalCount: Int,
        currentPhotoName: String?
    ): ForegroundInfo {
        ensureChannel()
        val title = when {
            totalCount <= 0 -> "Preparing your upload"
            uploadedCount >= totalCount -> "Finishing your post"
            else -> "Uploading photo ${(uploadedCount + 1).coerceAtMost(totalCount)} of $totalCount"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(currentPhotoName ?: "Sending your photos to Pixelfed")
            .setProgress(totalCount.coerceAtLeast(0), uploadedCount.coerceAtLeast(0), totalCount <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Removes the ongoing notification once the worker has stopped running. */
    fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Photo uploads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL = "photo_uploads"

        /** One upload at a time, so a stable id is enough to replace the progress in place. */
        private const val NOTIFICATION_ID = 20500
        private const val FOREGROUND_SERVICE_TYPE_DATA_SYNC =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
