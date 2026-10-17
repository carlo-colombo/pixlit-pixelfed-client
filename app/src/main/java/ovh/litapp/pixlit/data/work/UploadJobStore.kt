package ovh.litapp.pixlit.data.work

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The durable description of one upload (UP-05 / 04 F-05).
 *
 * It is stored under `cacheDir/upload-jobs` and referenced from WorkManager only by [uploadId],
 * because `Data` is capped at 10 KB and a caption plus per-photo alt text can exceed that. The same
 * id is the `Idempotency-Key`, so re-running the work after a lost response is deduplicated
 * server-side instead of creating a second status.
 */
data class UploadJob(
    val uploadId: String,
    val imageUris: List<String>,
    val caption: String,
    val altTexts: Map<String, String> = emptyMap(),
    val resizeTo8Mb: Boolean = false,
    val collectionIds: List<String> = emptyList(),
    val placeId: String? = null,
    /** Media ids already uploaded, parallel to [UploadJob.imageUris]; survives a retry. */
    val mediaIds: List<String?> = emptyList()
)

/**
 * Reads and writes [UploadJob] payloads, and prunes the aborted ones. The payload has to outlive the
 * process: that is what turns a back-press or a process death mid-upload into a resumable job
 * instead of a lost post.
 */
@Singleton
class UploadJobStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {

    private val jobDir: File by lazy { File(context.cacheDir, JOB_DIR) }

    suspend fun save(job: UploadJob) = withContext(Dispatchers.IO) {
        try {
            jobDir.mkdirs()
            fileFor(job.uploadId).writeText(gson.toJson(job))
        } catch (e: Exception) {
            Log.e(TAG, "save failed for ${job.uploadId}", e)
        }
    }

    suspend fun load(uploadId: String): UploadJob? = withContext(Dispatchers.IO) {
        try {
            val file = fileFor(uploadId)
            if (!file.exists()) return@withContext null
            gson.fromJson(file.readText(), UploadJob::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "load failed for $uploadId", e)
            null
        }
    }

    suspend fun delete(uploadId: String) = withContext(Dispatchers.IO) {
        if (!fileFor(uploadId).delete()) {
            Log.w(TAG, "delete failed for $uploadId")
        }
    }

    /**
     * Deletes payloads of uploads that were cancelled or whose process died before the worker could
     * clean up. They are unusable without a running work request, so the age bound only has to keep
     * a job that is still queued.
     */
    suspend fun prune(maxAgeMs: Long = MAX_AGE_MS) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        jobDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private fun fileFor(uploadId: String) = File(jobDir, "$uploadId.json")

    companion object {
        private const val TAG = "UploadJobStore"
        private const val JOB_DIR = "upload-jobs"

        /** WorkManager's longest backoff window for a one-shot request is well under a day. */
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
