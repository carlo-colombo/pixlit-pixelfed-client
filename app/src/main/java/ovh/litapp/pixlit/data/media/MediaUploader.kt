package ovh.litapp.pixlit.data.media

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import ovh.litapp.pixlit.data.NotLoggedInException
import ovh.litapp.pixlit.data.api.MAX_COLLECTIONS_PER_POST
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.api.StatusResponse
import ovh.litapp.pixlit.data.asPixelfedHttpException
import ovh.litapp.pixlit.data.auth.TokenManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The source image could not be opened or copied into the upload temp file. Typed so the upload
 * caller can distinguish "cannot read the source" from a resize or network failure (DATA-13).
 */
class ImageAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One photo finished uploading; the index lets the caller checkpoint a partially uploaded post. */
data class PhotoUploadStep(
    val index: Int,
    val mediaId: String,
    val displayName: String,
    val uploadedCount: Int,
    val totalCount: Int
)

/**
 * Multipart media upload + post creation (DATA-06 step 4 / F-12), the single seam the upload worker
 * calls. The temp-file lifecycle belongs to [UploadFileStore] (UP-07) and the bytes of each part are
 * the files it prepared (UP-08); this class owns the per-image request, the per-image alt text
 * (UP-10) and the `Idempotency-Key` that makes a retried post safe (UP-05 / 04 F-05).
 */
@Singleton
class MediaUploader @Inject constructor(
    private val tokenManager: TokenManager,
    private val apiFactory: PixelfedApiFactory,
    private val fileStore: UploadFileStore
) {

    /** The shared prepared file for ([uri], [resize]); see [UploadFileStore.prepare]. */
    suspend fun prepareImage(uri: Uri, resize: Boolean): Result<PreparedImage> =
        fileStore.prepare(uri, resize)

    /**
     * Uploads every image that is not yet in [alreadyUploaded] and creates the status.
     *
     * [alreadyUploaded] is parallel to [imageUris]: a non-null entry is a media id a previous
     * attempt already uploaded for that index, so a retry never re-uploads media (UP-05). Each
     * prepared file is deleted as soon as its request completes, whatever the outcome (UP-07).
     */
    suspend fun uploadPhotosAndCreateStatus(
        imageUris: List<Uri>,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null,
        altTexts: Map<Uri, String> = emptyMap(),
        idempotencyKey: String,
        alreadyUploaded: List<String?> = emptyList(),
        onPhotoUploaded: suspend (PhotoUploadStep) -> Unit = {}
    ): Result<StatusResponse> = withContext(Dispatchers.IO) {
        if (imageUris.isEmpty()) {
            return@withContext Result.failure(Exception("No images selected for upload"))
        }

        val instanceUrl = tokenManager.instanceUrl ?: return@withContext Result.failure(NotLoggedInException())
        val accessToken = tokenManager.accessToken ?: return@withContext Result.failure(NotLoggedInException())
        val api = apiFactory.get(instanceUrl)
        val mediaIds = MutableList<String?>(imageUris.size) { alreadyUploaded.getOrNull(it) }

        try {
            for ((index, uri) in imageUris.withIndex()) {
                if (mediaIds[index] != null) continue
                val prepared = fileStore.prepare(uri, resizeTo8Mb).getOrElse {
                    return@withContext Result.failure(it)
                }
                try {
                    val part = MultipartBody.Part.createFormData(
                        "file",
                        prepared.fileName,
                        prepared.file.asRequestBody(prepared.mimeType.toMediaTypeOrNull())
                    )
                    val mediaResponse = api.uploadMedia(
                        authHeader = "Bearer $accessToken",
                        file = part,
                        description = altTextToSend(altTexts[uri])
                    )
                    val mediaBody = mediaResponse.body()
                    if (!mediaResponse.isSuccessful || mediaBody == null) {
                        return@withContext Result.failure(
                            mediaResponse.asPixelfedHttpException("POST api/v1/media")
                        )
                    }
                    val mediaId = mediaBody.getIdString()
                        ?: return@withContext Result.failure(
                            Exception("Media upload response missing ID")
                        )
                    mediaIds[index] = mediaId
                    onPhotoUploaded(
                        PhotoUploadStep(
                            index = index,
                            mediaId = mediaId,
                            displayName = prepared.fileName,
                            uploadedCount = mediaIds.count { it != null },
                            totalCount = mediaIds.size
                        )
                    )
                } finally {
                    fileStore.release(prepared)
                }
            }

            val statusResponse = api.createStatus(
                authHeader = "Bearer $accessToken",
                headers = mapOf(IDEMPOTENCY_HEADER to idempotencyKey),
                status = caption,
                mediaIds = mediaIds.filterNotNull(),
                // Attached atomically by the status creator: no post-create "add" call can silently
                // 404 while the UI still reports the post as collected. The server caps this array
                // at 3 and would reject the whole post above that, so clamp defensively for drafts
                // restored from an older build.
                collectionIds = collectionIds.take(MAX_COLLECTIONS_PER_POST),
                placeId = placeId
            )
            val createdStatus = statusResponse.body()
            if (!statusResponse.isSuccessful || createdStatus == null) {
                return@withContext Result.failure(
                    statusResponse.asPixelfedHttpException("POST api/v1/statuses")
                )
            }
            // The idempotency key covers the status and its collection attachments together, so a
            // retried post is not duplicated and does not need a second attach request.
            Result.success(createdStatus)
        } catch (e: CancellationException) {
            // Cancelling an upload must propagate, not be reported as a failed upload (UP-13).
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The `description` part for one photo, or `null` when the user gave no alt text. The caption is
     * never used here (UP-10 / 04 F-16): an over-long caption used to 422 `POST api/v1/media` after
     * media had already been uploaded and orphaned.
     */
    private fun altTextToSend(altText: String?) =
        altText?.trim()?.take(MAX_ALT_CHARS)?.takeIf { it.isNotEmpty() }
            ?.toRequestBody(TEXT_PLAIN_TYPE)

    companion object {
        /**
         * Defensive clamp on a media `description`. Pixelfed does not publish the media-description
         * limit in its API reference, and the server rejects an over-long one with a 4xx that lands
         * *after* the file has been transferred; 1000 characters is comfortably below the caption
         * limit every Pixelfed version accepts, so the clamp can only ever shorten real alt text.
         */
        const val MAX_ALT_CHARS = 1000

        /** Stable per post, so a retry of a lost `createStatus` response is deduplicated. */
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"

        private val TEXT_PLAIN_TYPE = "text/plain".toMediaTypeOrNull()
    }
}
