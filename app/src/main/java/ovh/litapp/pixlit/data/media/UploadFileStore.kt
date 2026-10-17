package ovh.litapp.pixlit.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ovh.litapp.pixlit.utils.ImageUtils
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A cache file that is ready to be sent as a multipart part, with the `Content-Type` and filename
 * that describe its actual bytes (UP-11).
 */
data class PreparedImage(
    val uri: Uri,
    val file: File,
    val mimeType: String,
    val fileName: String
)

/**
 * Owns the upload temp files under `cacheDir/uploads` (UP-07 / UP-08 / UP-11).
 *
 * One prepared file per `(uri, resize)` is memoized for the process, so the preview path and the
 * upload path ask for the same key and the decode/encode work happens once per photo (UP-08).
 * [release] deletes the file as soon as the multipart request that used it completes, and the
 * directory is swept the first time it is touched in a process, so neither a crash nor a cancelled
 * upload can leave originals in cache (UP-07).
 *
 * Format policy (UP-11): the part's `Content-Type` and filename describe the bytes actually sent. A
 * source at or below [ImageUtils.MAX_BYTES_8MB] is copied verbatim, so its real mime type and
 * display name are preserved. Anything larger is re-encoded to JPEG: that drops EXIF (including
 * GPS, so no location leaks into the upload — REVIEW 06) and flattens transparency onto black,
 * accepted because the alternative is uploading a file the server rejects for size. The temp file's
 * own name is a UUID and never reaches the wire, so a `.jpg` name cannot mislabel a HEIC part.
 */
@Singleton
class UploadFileStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val uploadDir: File by lazy { File(context.cacheDir, UPLOAD_DIR) }

    /** Keyed by [key]; guarded by [lock] because the worker thread and the UI thread both prepare. */
    private val prepared = HashMap<String, PreparedImage>()
    private val lock = Any()

    @Volatile
    private var staleFilesCleared = false

    /**
     * The one prepared file for ([uri], [resize]): reused when it already exists, otherwise
     * copied (or resized) into the upload cache. Never throws for a bad URI; the failure is typed.
     */
    suspend fun prepare(uri: Uri, resize: Boolean): Result<PreparedImage> = withContext(Dispatchers.IO) {
        val key = key(uri, resize)
        synchronized(lock) { prepared[key] }?.takeIf { it.file.exists() }?.let {
            return@withContext Result.success(it)
        }
        clearStaleFilesOnce()
        try {
            val image = createPreparedImage(uri, resize)
            synchronized(lock) { prepared[key] = image }
            Result.success(image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed for $uri", e)
            Result.failure(ImageAccessException("Cannot read the selected image ($uri)", e))
        }
    }

    /** Deletes [image] and forgets it, so a later [prepare] re-creates it instead of using a dead file. */
    fun release(image: PreparedImage) {
        synchronized(lock) { prepared.entries.removeAll { it.value.file == image.file } }
        if (image.file.exists() && !image.file.delete()) {
            Log.w(TAG, "release: could not delete ${image.file.name}")
        }
    }

    private fun createPreparedImage(uri: Uri, resize: Boolean): PreparedImage {
        val sourceMime = resolveMimeType(uri)
        val sourceName = resolveDisplayName(uri, sourceMime)
        val sourceSize = ImageUtils.getImageMetadata(context, uri)?.sizeBytes
        val reencode = resize && sourceSize != null && sourceSize > ImageUtils.MAX_BYTES_8MB

        if (!reencode) {
            return PreparedImage(uri, copyToUploadDir(uri, sourceName), sourceMime, sourceName)
        }
        val resized = ImageUtils.resizeImageDownToMaxBytes(context, uri, ImageUtils.MAX_BYTES_8MB)
            ?: throw ImageAccessException("Unable to resize the selected image ($uri)")
        val file = moveIntoUploadDir(resized)
            ?: throw ImageAccessException("Unable to prepare the resized image ($uri)")
        return PreparedImage(
            uri = uri,
            file = file,
            mimeType = JPEG_MIME_TYPE,
            fileName = sourceName.substringBeforeLast('.', sourceName) + ".jpg"
        )
    }

    /** Copies the source bytes verbatim, so a HEIC/PNG/WebP part keeps its own format on the wire. */
    private fun copyToUploadDir(uri: Uri, sourceName: String): File {
        val extension = sourceName.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: DEFAULT_EXTENSION
        val target = File(uploadDir.apply { mkdirs() }, "$TEMP_PREFIX${UUID.randomUUID()}.$extension")
        try {
            FileOutputStream(target).use { output ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.copyTo(output)
                } ?: throw ImageAccessException("Cannot read the selected image ($uri): no input stream")
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        return target
    }

    /**
     * Moves a file [ImageUtils] created (it always writes into `cacheDir` itself) into the upload
     * directory, so the startup sweep owns every temp file the upload path creates.
     */
    private fun moveIntoUploadDir(source: File): File? {
        val target = File(uploadDir.apply { mkdirs() }, "$TEMP_PREFIX${UUID.randomUUID()}.jpg")
        if (source.renameTo(target)) return target
        return try {
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        } catch (t: Throwable) {
            Log.w(TAG, "moveIntoUploadDir failed", t)
            target.delete()
            source.delete()
            null
        }
    }

    private fun resolveMimeType(uri: Uri): String =
        context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: DEFAULT_MIME_TYPE

    private fun resolveDisplayName(uri: Uri, mimeType: String): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() && it.contains('.') }
            ?: "$DEFAULT_NAME.${extensionFor(mimeType)}"
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> DEFAULT_EXTENSION
    }

    /**
     * Deletes everything left in the upload directory by a previous process. Run on first use
     * rather than from `PixlitApplication.onCreate`: the directory only ever holds files this class
     * created, and a process that never uploads should not touch the cache.
     */
    private fun clearStaleFilesOnce() {
        if (staleFilesCleared) return
        synchronized(lock) {
            if (staleFilesCleared) return
            staleFilesCleared = true
            uploadDir.listFiles()?.forEach { file ->
                if (!file.delete()) Log.w(TAG, "clearStaleFilesOnce: could not delete ${file.name}")
            }
        }
    }

    private fun key(uri: Uri, resize: Boolean): String = "$uri|$resize"

    companion object {
        private const val TAG = "UploadFileStore"
        private const val UPLOAD_DIR = "uploads"
        private const val TEMP_PREFIX = "upload_"
        private const val DEFAULT_NAME = "photo"
        private const val DEFAULT_EXTENSION = "jpg"
        private const val DEFAULT_MIME_TYPE = "image/jpeg"
        private const val JPEG_MIME_TYPE = "image/jpeg"
    }
}
