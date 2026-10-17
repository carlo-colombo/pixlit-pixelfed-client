package ovh.litapp.pixlit.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Geocoder
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Hard upper bound on the pixels decoded from a single source image.
     *
     * A byte-size target of [MAX_BYTES_8MB] on a 48 MP photo asks for ~58% of the
     * source, which a power-of-two `inSampleSize` cannot express, so the classic
     * halving loop would decode at full resolution (UP-01). Bounding the decoded
     * pixel count keeps the peak allocation independent of the source size.
     */
    internal const val MAX_DECODE_PIXELS = 12_000_000

    private const val MIN_DIMENSION = 100
    private const val TAG = "ImageUtils"

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

    fun extractExifLocation(context: Context, uri: Uri): Pair<Double, Double>? {
        val targetUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && uri.scheme == "content") {
            try {
                android.provider.MediaStore.setRequireOriginal(uri)
            } catch (e: Throwable) {
                uri
            }
        } else {
            uri
        }

        var location = tryReadExifLocationFromStream(context, targetUri)
        if (location != null) return location

        val tempFile = copyUriToTempFile(context, targetUri) ?: copyUriToTempFile(context, uri)
        if (tempFile != null) {
            try {
                val exif = ExifInterface(tempFile.absolutePath)
                location = parseLatLongFromExif(exif)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    tempFile.delete()
                } catch (e: Exception) {
                    // Ignore deletion failure
                }
            }
        }
        return location
    }

    fun cityNameFromLocation(context: Context, latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    val city = address.locality ?: address.subAdminArea ?: address.adminArea
                    val country = address.countryName
                    listOfNotNull(city, country).joinToString(", ").takeIf { it.isNotBlank() }
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun tryReadExifLocationFromStream(context: Context, uri: Uri): Pair<Double, Double>? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                parseLatLongFromExif(exif)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLatLongFromExif(exif: ExifInterface): Pair<Double, Double>? {
        val latLong = exif.latLong
        if (latLong != null && latLong.size >= 2 && (latLong[0] != 0.0 || latLong[1] != 0.0)) {
            return Pair(latLong[0], latLong[1])
        }

        val latAttr = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
        val lngAttr = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
        val lngRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)

        if (!latAttr.isNullOrBlank() && !latRef.isNullOrBlank() && !lngAttr.isNullOrBlank() && !lngRef.isNullOrBlank()) {
            val lat = convertDmsToDecimal(latAttr, latRef)
            val lng = convertDmsToDecimal(lngAttr, lngRef)
            if (lat != null && lng != null) {
                return Pair(lat, lng)
            }
        }
        return null
    }

    private fun convertDmsToDecimal(dms: String, ref: String): Double? {
        return try {
            val parts = dms.split(",")
            if (parts.size < 3) return null
            fun parseRational(s: String): Double {
                val pair = s.trim().split("/")
                return if (pair.size == 2) {
                    pair[0].trim().toDouble() / pair[1].trim().toDouble()
                } else {
                    pair[0].trim().toDouble()
                }
            }
            val degrees = parseRational(parts[0])
            val minutes = parseRational(parts[1])
            val seconds = parseRational(parts[2])
            var decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)
            if (ref.equals("S", ignoreCase = true) || ref.equals("W", ignoreCase = true)) {
                decimal = -decimal
            }
            decimal
        } catch (e: Exception) {
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
     *
     * Totality (UP-12): never throws. Any decode/encode/filesystem failure logs and returns `null`,
     * `OutOfMemoryError` on pathological input is caught defensively, and the temporary file is
     * deleted on every failure path so no partial `resized_upload_*` remains.
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

        var tempFile: File? = null
        try {
            val file = File.createTempFile("resized_upload_", ".jpg", context.cacheDir)
            tempFile = file

            // Initial scale derived from the byte-size ratio; capped at 1.0 so we never upscale.
            var scale = 0.95
            val ratio = Math.sqrt(maxSizeBytes.toDouble() / originalMetadata.sizeBytes.toDouble())
            if (ratio < 0.95) {
                scale = ratio
            }
            var quality = 90

            // Iteratively scale down pixel dimensions until file size is under maxSizeBytes
            while (true) {
                val targetWidth = (originalMetadata.width * scale).toInt().coerceAtLeast(MIN_DIMENSION)
                val targetHeight = (originalMetadata.height * scale).toInt().coerceAtLeast(MIN_DIMENSION)

                val bitmap = decodeBoundedBitmap(context, uri, targetWidth, targetHeight)
                if (bitmap == null) {
                    file.delete()
                    return null
                }

                // The bounded decode may return a bitmap smaller than requested; only shrink
                // further, never upscale.
                val outputBitmap = if (bitmap.width > targetWidth || bitmap.height > targetHeight) {
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also {
                        if (it != bitmap) bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                val written = try {
                    FileOutputStream(file).use { out ->
                        outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }
                } finally {
                    outputBitmap.recycle()
                }
                if (!written) {
                    file.delete()
                    return null
                }

                if (file.length() <= maxSizeBytes ||
                    (targetWidth <= MIN_DIMENSION && targetHeight <= MIN_DIMENSION)
                ) {
                    break
                }

                // Adjust scale for next iteration if still too big
                scale *= 0.85
                if (quality > 50) {
                    quality -= 10
                }
            }

            return file
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "resize failed: out of memory for $uri", e)
            tempFile?.delete()
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "resize failed for $uri", t)
            tempFile?.delete()
            return null
        }
    }

    /**
     * Decodes [uri] at (or near) [reqWidth]x[reqHeight] without ever allocating more than
     * [MAX_DECODE_PIXELS] pixels, applying the EXIF orientation.
     */
    internal fun decodeBoundedBitmap(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val orientation = getExifOrientation(context, uri)
        val swapped = isSwappedDimensions(orientation)

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val rawReqWidth = if (swapped) reqHeight else reqWidth
        val rawReqHeight = if (swapped) reqWidth else reqHeight

        options.inSampleSize = calculateBoundedInSampleSize(options, rawReqWidth, rawReqHeight)
        options.inJustDecodeBounds = false

        val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        return rotateBitmap(decodedBitmap, orientation)
    }

    /**
     * Picks an `inSampleSize` that (a) never decodes more than [MAX_DECODE_PIXELS] and
     * (b) does not decode smaller than the requested size when the source allows it, so the
     * caller never has to upscale.
     */
    internal fun calculateBoundedInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1

        val sourcePixels = options.outWidth.toLong() * options.outHeight.toLong()
        while (sourcePixels / (inSampleSize.toLong() * inSampleSize) > MAX_DECODE_PIXELS) {
            inSampleSize *= 2
        }

        val (height: Int, width: Int) = options.outHeight to options.outWidth
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
        var tempFile: File? = null
        return try {
            val file = File.createTempFile("original_upload_", ".jpg", context.cacheDir)
            tempFile = file
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: run {
                file.delete()
                return null
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "copy failed for $uri", t)
            tempFile?.delete()
            null
        }
    }
}
