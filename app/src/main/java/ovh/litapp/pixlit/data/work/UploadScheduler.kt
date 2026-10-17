package ovh.litapp.pixlit.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class UploadJobStatus { Running, Succeeded, Failed, Cancelled }

/** What the UI shows about the in-flight upload: per-photo progress plus the terminal outcome. */
data class UploadJobState(
    val status: UploadJobStatus,
    val uploadedCount: Int = 0,
    val totalCount: Int = 0,
    val currentPhotoName: String? = null,
    val errorMessage: String? = null
)

/**
 * The only way the app starts and watches an upload (UP-05). `UploadViewModel` enqueues a
 * [UploadJob] and collects [observe]; nothing uploads inside a `viewModelScope`, so the work
 * survives the screen and the process.
 */
@Singleton
class UploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobStore: UploadJobStore
) {

    /** Persists [job] and enqueues it; a second tap for the same job id is ignored (KEEP). */
    suspend fun enqueue(job: UploadJob) {
        jobStore.prune()
        jobStore.save(job)
        workManager().enqueueUniqueWork(
            UploadWorker.uniqueWorkName(job.uploadId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setId(UUID.fromString(job.uploadId))
                .setInputData(workDataOf(UploadWorker.KEY_UPLOAD_ID to job.uploadId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
        )
    }

    /**
     * Progress and the terminal state of [uploadId]. The flow re-emits on every WorkManager change
     * and is hot only while collected, so the ViewModel sees the state it missed while it was gone.
     */
    fun observe(uploadId: String): Flow<UploadJobState> =
        workManager().getWorkInfoByIdFlow(UUID.fromString(uploadId))
            .mapNotNull { it?.toUploadJobState() }

    /** Cancels the worker; WorkManager stops it and deletes the queued request. */
    fun cancel(uploadId: String) {
        workManager().cancelUniqueWork(UploadWorker.uniqueWorkName(uploadId))
    }

    private fun workManager(): WorkManager = WorkManager.getInstance(context)

    private fun WorkInfo.toUploadJobState(): UploadJobState {
        val status = when (state) {
            WorkInfo.State.SUCCEEDED -> UploadJobStatus.Succeeded
            WorkInfo.State.FAILED -> UploadJobStatus.Failed
            WorkInfo.State.CANCELLED -> UploadJobStatus.Cancelled
            else -> UploadJobStatus.Running
        }
        return UploadJobState(
            status = status,
            uploadedCount = outputData.getInt(UploadWorker.KEY_UPLOADED, progress.getInt(UploadWorker.KEY_UPLOADED, 0)),
            totalCount = outputData.getInt(UploadWorker.KEY_TOTAL, progress.getInt(UploadWorker.KEY_TOTAL, 0)),
            currentPhotoName = progress.getString(UploadWorker.KEY_CURRENT),
            errorMessage = outputData.getString(UploadWorker.KEY_ERROR)
        )
    }

    companion object {
        private const val TAG = "upload"

        /** First retry after 30 s, doubling; WorkManager caps the window at five hours. */
        private const val BACKOFF_SECONDS = 30L
    }
}
