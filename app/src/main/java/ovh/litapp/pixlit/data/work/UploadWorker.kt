package ovh.litapp.pixlit.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ovh.litapp.pixlit.data.PixelfedHttpException
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import java.io.IOException

/**
 * Runs one upload to completion in WorkManager instead of `viewModelScope` (UP-05 / 04 F-05), so
 * back-press, rotation or process death cannot drop a post. It reports per-photo progress through
 * the work request's `progress` data, retries transient failures with exponential backoff, and
 * checkpoints every uploaded media id in the job payload so a retry never re-uploads media and the
 * stable `Idempotency-Key` stops `createStatus` from duplicating the post.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: PixelfedRepository,
    private val jobStore: UploadJobStore,
    private val notifier: UploadNotifier
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uploadId = inputData.getString(KEY_UPLOAD_ID)
            ?: return Result.failure(errorData("The upload job is missing its id"))
        val job = jobStore.load(uploadId)
            ?: return Result.failure(errorData("The upload draft could not be read"))
        val imageUris = job.imageUris.map(Uri::parse)
        val mediaIds = MutableList<String?>(imageUris.size) { job.mediaIds.getOrNull(it) }

        setForeground(notifier.foregroundInfo(mediaIds.count { it != null }, imageUris.size, null))

        val result = repository.uploadPhotosAndCreateStatus(
            imageUris = imageUris,
            caption = job.caption,
            resizeTo8Mb = job.resizeTo8Mb,
            collectionIds = job.collectionIds,
            placeId = job.placeId,
            altTexts = job.altTexts.mapKeys { (uri, _) -> Uri.parse(uri) },
            idempotencyKey = uploadId,
            alreadyUploaded = mediaIds,
            onPhotoUploaded = { step ->
                mediaIds[step.index] = step.mediaId
                jobStore.save(job.copy(mediaIds = mediaIds.toList()))
                setProgress(progressData(step.uploadedCount, step.totalCount, step.displayName))
                setForeground(
                    notifier.foregroundInfo(step.uploadedCount, step.totalCount, step.displayName)
                )
            }
        )

        notifier.clear()
        val error = result.exceptionOrNull()
        if (error == null) {
            jobStore.delete(uploadId)
            return Result.success()
        }
        Log.w(TAG, "upload $uploadId failed on attempt $runAttemptCount", error)
        if (isRetryable(error) && runAttemptCount < MAX_ATTEMPTS) {
            // The payload and its media-id checkpoint stay on disk, so this attempt resumes rather
            // than re-uploading the photos that already went through.
            return Result.retry()
        }
        jobStore.delete(uploadId)
        return Result.failure(
            failureData(
                message = error.localizedMessage ?: error.message ?: error.toString(),
                uploadedCount = mediaIds.count { it != null },
                totalCount = imageUris.size
            )
        )
    }

    /** A dropped connection or a server-side hiccup is worth another attempt; a 4xx is not. */
    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is IOException -> true
        is PixelfedHttpException -> error.code == 429 || error.code >= 500
        else -> error.cause?.let(::isRetryable) ?: false
    }

    private fun progressData(uploadedCount: Int, totalCount: Int, currentPhotoName: String?) =
        workDataOf(
            KEY_UPLOADED to uploadedCount,
            KEY_TOTAL to totalCount,
            KEY_CURRENT to currentPhotoName
        )

    private fun errorData(message: String) = failureData(message, 0, 0)

    /** The terminal failure the UI renders, including how many photos did make it (UP-05). */
    private fun failureData(message: String, uploadedCount: Int, totalCount: Int) = workDataOf(
        KEY_ERROR to message,
        KEY_UPLOADED to uploadedCount,
        KEY_TOTAL to totalCount
    )

    companion object {
        private const val TAG = "UploadWorker"

        /** How many times a transient failure is retried before the post is reported as failed. */
        private const val MAX_ATTEMPTS = 3

        const val KEY_UPLOAD_ID = "uploadId"
        const val KEY_UPLOADED = "uploadedCount"
        const val KEY_TOTAL = "totalCount"
        const val KEY_CURRENT = "currentPhotoName"
        const val KEY_ERROR = "uploadError"

        /** The unique-work name of one upload; [UploadScheduler] cancels by the same name. */
        fun uniqueWorkName(uploadId: String) = "upload-$uploadId"
    }
}
