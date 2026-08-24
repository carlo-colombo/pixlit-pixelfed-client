package ovh.litapp.pixlit.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class ImageUtilsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createTestImageFile(width: Int, height: Int, filename: String = "test.jpg"): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        val paint = Paint().apply { color = Color.BLUE }
        // Draw some pattern so it compresses with non-zero size
        for (i in 0 until width step 10) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), paint)
        }

        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    @Test
    fun testImageMetadataFormatting() {
        val metadataSmall = ImageMetadata(sizeBytes = 500, width = 100, height = 200)
        assertEquals("500 B", metadataSmall.formatFileSize())
        assertEquals("100 × 200 px", metadataSmall.formatDimensions())

        val metadataKb = ImageMetadata(sizeBytes = 1500, width = 800, height = 600)
        assertEquals("1.5 KB", metadataKb.formatFileSize())

        val metadataMb = ImageMetadata(sizeBytes = 10 * 1024 * 1024L, width = 4000, height = 3000)
        assertEquals("10.00 MB", metadataMb.formatFileSize())
        assertEquals("4000 × 3000 px", metadataMb.formatDimensions())
    }

    @Test
    fun testGetFileMetadata() {
        val file = createTestImageFile(400, 300)
        val metadata = ImageUtils.getFileMetadata(file)

        assertNotNull(metadata)
        assertEquals(400, metadata!!.width)
        assertEquals(300, metadata.height)
        assertTrue(metadata.sizeBytes > 0)
    }

    @Test
    fun testGetImageMetadataFromUri() {
        val file = createTestImageFile(500, 250)
        val uri = Uri.fromFile(file)
        val metadata = ImageUtils.getImageMetadata(context, uri)

        assertNotNull(metadata)
        assertEquals(500, metadata!!.width)
        assertEquals(250, metadata.height)
        assertTrue(metadata.sizeBytes > 0)
    }

    @Test
    fun testResizeImageDownToMaxBytesSmallImageUnchanged() {
        val file = createTestImageFile(100, 100)
        val uri = Uri.fromFile(file)

        val resultFile = ImageUtils.resizeImageDownToMaxBytes(context, uri, maxSizeBytes = ImageUtils.MAX_BYTES_8MB)
        assertNotNull(resultFile)
        assertTrue(resultFile!!.length() <= ImageUtils.MAX_BYTES_8MB)

        val resultMetadata = ImageUtils.getFileMetadata(resultFile)
        assertNotNull(resultMetadata)
        assertEquals(100, resultMetadata!!.width)
        assertEquals(100, resultMetadata.height)
    }

    @Test
    fun testResizeImageDownToCustomSmallMaxBytes() {
        // Create large image file
        val file = createTestImageFile(2000, 2000, "large.jpg")
        val uri = Uri.fromFile(file)
        val originalMetadata = ImageUtils.getImageMetadata(context, uri)!!

        val customMaxBytes = 50 * 1024L // 50 KB
        val resizedFile = ImageUtils.resizeImageDownToMaxBytes(context, uri, maxSizeBytes = customMaxBytes)

        assertNotNull(resizedFile)
        assertTrue("Resized file length (${resizedFile!!.length()}) should be <= $customMaxBytes", resizedFile.length() <= customMaxBytes)

        val resizedMetadata = ImageUtils.getFileMetadata(resizedFile)!!
        assertTrue(resizedMetadata.width < originalMetadata.width)
        assertTrue(resizedMetadata.height < originalMetadata.height)
    }
}
