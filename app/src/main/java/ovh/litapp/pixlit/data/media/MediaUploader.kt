package ovh.litapp.pixlit.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.api.StatusResponse
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.utils.ImageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The source image could not be opened or copied into the upload temp file. Typed so the upload
 * caller can distinguish "cannot read the source" from a resize or network failure (DATA-13).
 */
class ImageAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Multipart media upload + post creation (DATA-06 step 4 / F-12). Owns the temp-file lifecycle for
 * non-resized photos; `ImageUtils` owns its own resized copy.
 */
@Singleton
class MediaUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
    private val apiFactory: PixelfedApiFactory
) {

    suspend fun uploadPhotoAndCreateStatus(
        imageUri: Uri,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null
    ): Result<StatusResponse> {
        return uploadPhotosAndCreateStatus(listOf(imageUri), caption, resizeTo8Mb, collectionIds, placeId)
    }

    suspend fun uploadPhotosAndCreateStatus(
        imageUris: List<Uri>,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null
    ): Result<StatusResponse> = withContext(Dispatchers.IO) {
        if (imageUris.isEmpty()) {
            return@withContext Result.failure(Exception("No images selected for upload"))
        }

        try {
            val instanceUrl = tokenManager.instanceUrl ?: return@withContext Result.failure(Exception("Not logged in"))
            val accessToken = tokenManager.accessToken ?: return@withContext Result.failure(Exception("Not logged in"))

            val api = apiFactory.get(instanceUrl)
            val mediaIds = mutableListOf<String>()

            for (uri in imageUris) {
                val file = if (resizeTo8Mb) {
                    ImageUtils.resizeImageDownToMaxBytes(context, uri, ImageUtils.MAX_BYTES_8MB)
                        ?: return@withContext Result.failure(Exception("Unable to resize the selected image"))
                } else {
                    getFileFromUri(uri).getOrElse { return@withContext Result.failure(it) }
                }

                val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                val multipartBody = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val descRequestBody = caption.toRequestBody("text/plain".toMediaTypeOrNull())

                val mediaResponse = api.uploadMedia(
                    authHeader = "Bearer $accessToken",
                    file = multipartBody,
                    description = descRequestBody
                )

                if (!mediaResponse.isSuccessful || mediaResponse.body() == null) {
                    return@withContext Result.failure(Exception("Media upload failed: ${mediaResponse.code()} ${mediaResponse.errorBody()?.string()}"))
                }

                val mediaId = mediaResponse.body()!!.getIdString()
                    ?: return@withContext Result.failure(Exception("Media upload response missing ID"))

                mediaIds.add(mediaId)
            }

            val statusResponse = api.createStatus(
                authHeader = "Bearer $accessToken",
                status = caption,
                mediaIds = mediaIds,
                placeId = placeId
            )

            if (statusResponse.isSuccessful && statusResponse.body() != null) {
                val createdStatus = statusResponse.body()!!
                val statusId = createdStatus.getIdString()
                if (!statusId.isNullOrBlank() && collectionIds.isNotEmpty()) {
                    for (collectionId in collectionIds) {
                        try {
                            api.addStatusToCollection(
                                authHeader = "Bearer $accessToken",
                                collectionId = collectionId,
                                statusId = statusId
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add status $statusId to collection $collectionId", e)
                        }
                    }
                }
                Result.success(createdStatus)
            } else {
                Result.failure(Exception("Status creation failed: ${statusResponse.code()} ${statusResponse.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getFileFromUri(uri: Uri): Result<File> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(
                    ImageAccessException("Cannot read the selected image ($uri): no input stream")
                )
            inputStream.use { input ->
                val tempFile = File.createTempFile("upload_", ".jpg", context.cacheDir)
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
                Result.success(tempFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFileFromUri failed for $uri", e)
            Result.failure(ImageAccessException("Cannot read the selected image ($uri)", e))
        }
    }

    companion object {
        private const val TAG = "MediaUploader"
    }
}
