package ovh.litapp.pixlit.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

data class ImageMetadata(
    val sizeBytes: Long,
    val width: Int,
    val height: Int
) {
    fun formatFileSize(): String {
        if (sizeBytes <= 0) return "0 B"
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB", mb)
        } else if (kb >= 1.0) {
            String.format(Locale.US, "%.1f KB", kb)
        } else {
            "$sizeBytes B"
        }
    }

    fun formatDimensions(): String {
        return if (width > 0 && height > 0) {
            "$width × $height px"
        } else {
            "Unknown size"
        }
    }
}

object ImageUtils {
    const val MAX_BYTES_8MB = 8 * 1024 * 1024L // 8 MB

    fun getImageMetadata(context: Context, uri: Uri): ImageMetadata? {
        val sizeBytes = getFileSize(context, uri) ?: return null
        val dimensions = getImageDimensions(context, uri) ?: return null
        return ImageMetadata(sizeBytes, dimensions.first, dimensions.second)
    }

    fun getFileMetadata(file: File): ImageMetadata? {
        if (!file.exists()) return null
        val sizeBytes = file.length()
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val orientation = try {
            val exif = ExifInterface(file.absolutePath)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val (w, h) = if (isSwappedDimensions(orientation)) {
            Pair(options.outHeight, options.outWidth)
        } else {
            Pair(options.outWidth, options.outHeight)
        }

        return ImageMetadata(sizeBytes, w, h)
    }

    private fun getFileSize(context: Context, uri: Uri): Long? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) return file.length()
                }
            }
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    val size = cursor.getLong(sizeIndex)
                    if (size > 0) return size
                }
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                var totalBytes = 0L
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                }
                if (totalBytes > 0) totalBytes else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                val orientation = getExifOrientation(context, uri)
                if (isSwappedDimensions(orientation)) {
                    Pair(options.outHeight, options.outWidth)
                } else {
                    Pair(options.outWidth, options.outHeight)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            e.printStackTrace()
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun isSwappedDimensions(orientation: Int): Boolean {
        return orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    /**
     * Resizes the image at [uri] down so its file size is <= [maxSizeBytes] by reducing pixel dimensions
     * and compressing as JPEG.
     */
    fun resizeImageDownToMaxBytes(
        context: Context,
        uri: Uri,
        maxSizeBytes: Long = MAX_BYTES_8MB
    ): File? {
        val originalMetadata = getImageMetadata(context, uri) ?: return null

        // If file is already <= maxSizeBytes, copy to temp file and return
        if (originalMetadata.sizeBytes <= maxSizeBytes) {
            return copyUriToTempFile(context, uri)
        }

        var currentWidth = originalMetadata.width
        var currentHeight = originalMetadata.height
        var quality = 90

        val tempFile = File.createTempFile("resized_upload_", ".jpg", context.cacheDir)

        // Iteratively scale down pixel dimensions until file size is under maxSizeBytes
        var scale = 0.95
        val ratio = Math.sqrt(maxSizeBytes.toDouble() / originalMetadata.sizeBytes.toDouble())
        if (ratio < 0.95) {
            scale = ratio
        }

        while (true) {
            currentWidth = (currentWidth * scale).toInt().coerceAtLeast(100)
            currentHeight = (currentHeight * scale).toInt().coerceAtLeast(100)

            val bitmap = decodeSampledBitmapFromUri(context, uri, currentWidth, currentHeight)
                ?: return null

            val scaledBitmap = if (bitmap.width != currentWidth || bitmap.height != currentHeight) {
                Bitmap.createScaledBitmap(bitmap, currentWidth, currentHeight, true)
            } else {
                bitmap
            }

            val outStream = FileOutputStream(tempFile)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            outStream.close()

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            if (tempFile.length() <= maxSizeBytes || (currentWidth <= 100 && currentHeight <= 100)) {
                break
            }

            // Adjust scale for next iteration if still too big
            scale = 0.85
            if (quality > 50) {
                quality -= 10
            }
        }

        return tempFile
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            val orientation = getExifOrientation(context, uri)
            val swapped = isSwappedDimensions(orientation)

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val rawReqWidth = if (swapped) reqHeight else reqWidth
            val rawReqHeight = if (swapped) reqWidth else reqHeight

            options.inSampleSize = calculateInSampleSize(options, rawReqWidth, rawReqHeight)
            options.inJustDecodeBounds = false

            val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            rotateBitmap(decodedBitmap, orientation)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun copyUriToTempFile(context: Context, uri: Uri): File? {
        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File.createTempFile("original_upload_", ".jpg", context.cacheDir)
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
