package ovh.litapp.pixlit.ui.upload

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonPrimitive
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.litapp.pixlit.data.Fetch
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.media.PreparedImage
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.data.repository.TagCount
import ovh.litapp.pixlit.data.work.UploadJob
import ovh.litapp.pixlit.data.work.UploadJobState
import ovh.litapp.pixlit.data.work.UploadJobStatus
import ovh.litapp.pixlit.data.work.UploadScheduler
import ovh.litapp.pixlit.utils.ImageMetadata
import ovh.litapp.pixlit.utils.ImageUtils
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UploadViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<PixelfedRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val uploadScheduler = mockk<UploadScheduler>(relaxed = true)

    private lateinit var viewModel: UploadViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { repository.getDefaultStaticTags() } returns listOf("#tag1", "#tag2")
        // Stubbed so a caption edit's tag search gets a real `Result<List<String>>` instead of the
        // relaxed mock's erased `Object`, which cannot be cast back to a List.
        coEvery { repository.searchTags(any()) } returns Result.success(emptyList())
        coEvery { repository.getUserTopTagsAndPosts(any()) } answers {
            Fetch.Fresh(
                PixelfedRepository.TagsAndPosts(
                    listOf(
                        TagCount("tag1", 2),
                        TagCount("tag2", 1)
                    ),
                    emptyList()
                )
            )
        }
        coEvery { repository.getUserCollections() } returns Fetch.Fresh(
            listOf(
                CollectionItem(id = JsonPrimitive("col1"), title = "Collection 1"),
                CollectionItem(id = JsonPrimitive("col2"), title = "Collection 2")
            )
        )
        // The upload itself runs in WorkManager; the ViewModel only enqueues and observes, so the
        // tests drive the observed job state directly.
        every { uploadScheduler.observe(any()) } returns flowOf(
            UploadJobState(UploadJobStatus.Running, uploadedCount = 1, totalCount = 1),
            UploadJobState(UploadJobStatus.Succeeded, uploadedCount = 1, totalCount = 1)
        )
        viewModel = UploadViewModel(context, repository, uploadScheduler, SavedStateHandle())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Drains work parked on the real `Dispatchers.IO` used by `updateMetadata`/`detectExifLocation`,
     * then flushes the resumptions they schedule back onto the test dispatcher. `advanceUntilIdle`
     * alone cannot see a coroutine suspended on a real dispatcher.
     */
    private fun TestScope.settleIo(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(2)
        }
        advanceUntilIdle()
    }

    @Test
    fun `addImages updates selectedImageUris`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        val uris = listOf(uri1, uri2)

        viewModel.addImages(uris)
        advanceUntilIdle()

        assertEquals(uris, viewModel.uiState.value.selectedImageUris)
    }

    @Test
    fun `removeImageAt updates selectedImageUris`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1, uri2))
        advanceUntilIdle()

        viewModel.removeImageAt(0)
        advanceUntilIdle()

        assertEquals(listOf(uri2), viewModel.uiState.value.selectedImageUris)
    }

    @Test
    fun `shiftRight swaps images and updates currentPage`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1, uri2))
        advanceUntilIdle()

        viewModel.shiftRight(0)
        advanceUntilIdle()

        assertEquals(listOf(uri2, uri1), viewModel.uiState.value.selectedImageUris)
        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `insertTag updates caption correctly`() = runTest {
        viewModel.insertTag("#photography (12)")
        assertEquals("#photography ", viewModel.uiState.value.caption.text)
    }

    @Test
    fun `insertTagSuggestion replaces the hashtag being typed`() = runTest {
        viewModel.onCaptionChanged(
            TextFieldValue("A photo of #phot", TextRange("A photo of #phot".length))
        )

        viewModel.insertTagSuggestion("photography")

        assertEquals("A photo of #photography ", viewModel.uiState.value.caption.text)
        assertEquals(
            "A photo of #photography ".length,
            viewModel.uiState.value.caption.selection.start
        )
    }

    @Test
    fun `findHashtagContext ignores hashtags embedded in words`() {
        assertEquals(
            HashtagContext("phot", 0, 5),
            findHashtagContext(TextFieldValue("#phot", TextRange(5)))
        )
        assertEquals(
            null,
            findHashtagContext(TextFieldValue("word#phot", TextRange(9)))
        )
    }

    @Test
    fun `toggleCollectionSelection allows selecting 0 1 or multiple collections`() = runTest {
        // Initially 0 collections selected
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedCollectionIds)

        // Select 1 collection
        viewModel.toggleCollectionSelection("col1")
        assertEquals(setOf("col1"), viewModel.uiState.value.selectedCollectionIds)

        // Select second collection (multiple)
        viewModel.toggleCollectionSelection("col2")
        assertEquals(setOf("col1", "col2"), viewModel.uiState.value.selectedCollectionIds)

        // Deselect col1
        viewModel.toggleCollectionSelection("col1")
        assertEquals(setOf("col2"), viewModel.uiState.value.selectedCollectionIds)

        // Deselect col2 -> back to 0 collections
        viewModel.toggleCollectionSelection("col2")
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedCollectionIds)
    }

    @Test
    fun `toggleCollectionSelection refuses a fourth collection`() = runTest {
        // The status creator validates `collection_ids` as max:3; a fourth would 422 the post.
        viewModel.toggleCollectionSelection("col1")
        viewModel.toggleCollectionSelection("col2")
        viewModel.toggleCollectionSelection("col3")
        viewModel.toggleCollectionSelection("col4")

        assertEquals(setOf("col1", "col2", "col3"), viewModel.uiState.value.selectedCollectionIds)
        assertEquals(
            "A post can be added to at most 3 collections",
            viewModel.uiState.value.statusMessage
        )
    }

    @Test
    fun `upload enqueues the selected collection IDs and clears the draft on success`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1))
        viewModel.toggleCollectionSelection("col1")
        viewModel.toggleCollectionSelection("col2")

        viewModel.upload()
        advanceUntilIdle()

        val enqueued = mutableListOf<UploadJob>()
        coVerify { uploadScheduler.enqueue(capture(enqueued)) }
        val job = enqueued.single()
        assertEquals(listOf(uri1.toString()), job.imageUris)
        assertEquals("", job.caption)
        assertEquals(false, job.resizeTo8Mb)
        assertEquals(setOf("col1", "col2"), job.collectionIds.toSet())
        assertEquals(null, job.placeId)
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedCollectionIds)
        assertEquals(false, viewModel.uiState.value.isUploading)
    }

    @Test
    fun `refreshRecentPosts reloads statuses and updates refreshing state`() = runTest {
        val updatedStatus = StatusItem(id = JsonPrimitive("999"), content = "Refreshed post")
        coEvery { repository.getUserTopTagsAndPosts(forceRefresh = true) } returns Fetch.Fresh(
            PixelfedRepository.TagsAndPosts(
                topTags = listOf(TagCount("refreshedTag", 5)),
                statuses = listOf(updatedStatus)
            )
        )

        viewModel.refreshRecentPosts()
        advanceUntilIdle()

        coVerify { repository.getUserTopTagsAndPosts(forceRefresh = true) }
        assertEquals(listOf(updatedStatus), viewModel.uiState.value.recentStatuses)
        assertEquals(false, viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `selectPlace updates selectedPlace and clears custom location`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        assertEquals(place, viewModel.uiState.value.selectedPlace)
        assertEquals(null, viewModel.uiState.value.customLocationName)
        assertEquals(false, viewModel.uiState.value.isExifAutoDetected)
    }

    @Test
    fun `setCustomLocation updates customLocationName and clears selectedPlace`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        viewModel.setCustomLocation("Milano")

        assertEquals(null, viewModel.uiState.value.selectedPlace)
        assertEquals("Milano", viewModel.uiState.value.customLocationName)
        assertEquals(false, viewModel.uiState.value.isExifAutoDetected)
    }

    @Test
    fun `clearLocation clears all position selection state`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        viewModel.clearLocation()

        assertEquals(null, viewModel.uiState.value.selectedPlace)
        assertEquals(null, viewModel.uiState.value.customLocationName)
        assertEquals(false, viewModel.uiState.value.isExifAutoDetected)
    }

    @Test
    fun `onPlaceSearchQueryChanged performs debounced place search`() = runTest {
        val places = listOf(PlaceItem(id = JsonPrimitive("p10"), name = "Florence", country = "Italy"))
        coEvery { repository.searchPlaces(query = "Flor", lat = null, lng = null) } returns Fetch.Fresh(places)

        viewModel.onPlaceSearchQueryChanged("Flor")
        advanceTimeBy(301)
        advanceUntilIdle()

        assertEquals(places, viewModel.uiState.value.placeSearchResults)
    }

    @Test
    fun `upload with selected place enqueues the placeId`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1))

        val place = PlaceItem(id = JsonPrimitive("place_99"), name = "Naples")
        viewModel.selectPlace(place)

        viewModel.upload()
        advanceUntilIdle()

        val enqueued = mutableListOf<UploadJob>()
        coVerify { uploadScheduler.enqueue(capture(enqueued)) }
        val job = enqueued.single()
        assertEquals(listOf(uri1.toString()), job.imageUris)
        assertEquals("", job.caption)
        assertEquals(emptyList<String>(), job.collectionIds)
        assertEquals("place_99", job.placeId)
    }

    // --- TEST-02: failure paths -------------------------------------------------------------

    @Test
    fun `upload failure resets isUploading and keeps a retry possible`() = runTest {
        every { uploadScheduler.observe(any()) } returns flowOf(
            UploadJobState(UploadJobStatus.Running, uploadedCount = 0, totalCount = 1),
            UploadJobState(
                UploadJobStatus.Failed,
                uploadedCount = 0,
                totalCount = 1,
                errorMessage = "network down"
            )
        )
        val uri1 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1))

        viewModel.upload()
        advanceUntilIdle()

        val failed = viewModel.uiState.value
        assertEquals(false, failed.isUploading)
        assertEquals(true, failed.isError)
        assertEquals("Upload failed: network down", failed.statusMessage)

        // Retry: the failure cleared isUploading and kept the photos, so a second tap re-enqueues.
        viewModel.upload()
        advanceUntilIdle()
        coVerify(atLeast = 2) { uploadScheduler.enqueue(any()) }
    }

    @Test
    fun `cancelled upload resets isUploading and reports the cancellation`() = runTest {
        every { uploadScheduler.observe(any()) } returns flowOf(
            UploadJobState(UploadJobStatus.Running, uploadedCount = 1, totalCount = 2),
            UploadJobState(UploadJobStatus.Cancelled, uploadedCount = 1, totalCount = 2)
        )
        val uri1 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1))

        viewModel.upload()
        advanceUntilIdle()

        val cancelled = viewModel.uiState.value
        assertEquals(false, cancelled.isUploading)
        assertEquals(false, cancelled.isError)
        assertEquals("Upload cancelled", cancelled.statusMessage)
        assertEquals(UploadJobStatus.Cancelled, cancelled.uploadProgress?.status)
    }

    @Test
    fun `cancelUpload cancels the active work request`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1))

        viewModel.upload()
        advanceUntilIdle()

        val enqueued = mutableListOf<UploadJob>()
        coVerify { uploadScheduler.enqueue(capture(enqueued)) }
        viewModel.cancelUpload()

        verify { uploadScheduler.cancel(enqueued.single().uploadId) }
    }

    @Test
    fun `addImages caps the selection at six photos`() = runTest {
        val uris = (1..8).map { mockk<Uri>(relaxed = true) }

        viewModel.addImages(uris)
        advanceUntilIdle()

        assertEquals(uris.take(6), viewModel.uiState.value.selectedImageUris)
        assertEquals(true, viewModel.uiState.value.statusMessage?.contains("not added") == true)
    }

    @Test
    fun `addImages de-duplicates a URI picked twice`() = runTest {
        val uri = mockk<Uri>(relaxed = true)

        viewModel.addImages(listOf(uri, uri))
        advanceUntilIdle()

        assertEquals(listOf(uri), viewModel.uiState.value.selectedImageUris)
    }

    @Test
    fun `oversized source prepares a resized preview when resize is enabled`() = runTest {
        mockkObject(ImageUtils)
        val preparedFile = File.createTempFile("prepared", ".jpg")
        try {
            val uri = mockk<Uri>(relaxed = true)
            every { ImageUtils.extractExifLocation(any(), any()) } returns null
            every { ImageUtils.getImageMetadata(any(), any()) } returns
                ImageMetadata(9L * 1024 * 1024, 8000, 6000)
            every { ImageUtils.getFileMetadata(any()) } returns
                ImageMetadata(2L * 1024 * 1024, 3000, 2000)
            coEvery { repository.prepareImage(any(), true) } returns Result.success(
                PreparedImage(uri = uri, file = preparedFile, mimeType = "image/jpeg", fileName = "prepared.jpg")
            )

            viewModel.onResizeToggled(true)
            viewModel.addImages(listOf(uri))
            settleIo { viewModel.uiState.value.resizedMetadata != null }

            coVerify { repository.prepareImage(uri, true) }
            assertEquals(2L * 1024 * 1024, viewModel.uiState.value.resizedMetadata?.sizeBytes)
        } finally {
            unmockkObject(ImageUtils)
            preparedFile.delete()
        }
    }

    @Test
    fun `source under the eight megabyte bound is never re-encoded`() = runTest {
        mockkObject(ImageUtils)
        try {
            val uri = mockk<Uri>(relaxed = true)
            every { ImageUtils.extractExifLocation(any(), any()) } returns null
            every { ImageUtils.getImageMetadata(any(), any()) } returns
                ImageMetadata(1L * 1024 * 1024, 1024, 768)

            viewModel.onResizeToggled(true)
            viewModel.addImages(listOf(uri))
            settleIo { viewModel.uiState.value.originalMetadata != null }

            coVerify(exactly = 0) { repository.prepareImage(any(), any()) }
            assertEquals(null, viewModel.uiState.value.resizedMetadata)
        } finally {
            unmockkObject(ImageUtils)
        }
    }

    @Test
    fun `detected city is applied when no manual location is set`() = runTest {
        mockkObject(ImageUtils)
        try {
            val uri = mockk<Uri>(relaxed = true)
            every { ImageUtils.extractExifLocation(any(), any()) } returns Pair(41.9, 12.5)
            every { ImageUtils.cityNameFromLocation(any(), any(), any()) } returns "Rome, Italy"

            viewModel.detectExifLocation(listOf(uri))
            settleIo { viewModel.uiState.value.customLocationName != null }

            assertEquals("Rome, Italy", viewModel.uiState.value.customLocationName)
            assertEquals(true, viewModel.uiState.value.isExifAutoDetected)
            assertEquals(false, viewModel.uiState.value.isDetectingExifLocation)
        } finally {
            unmockkObject(ImageUtils)
        }
    }

    @Test
    fun `manual location chosen during EXIF detection is not clobbered`() = runTest {
        mockkObject(ImageUtils)
        try {
            val uri = mockk<Uri>(relaxed = true)
            val manual = PlaceItem(id = JsonPrimitive("p1"), name = "Milano", country = "Italy")
            every { ImageUtils.extractExifLocation(any(), any()) } returns Pair(41.9, 12.5)
            every { ImageUtils.cityNameFromLocation(any(), any(), any()) } returns "Rome, Italy"

            viewModel.detectExifLocation(listOf(uri))
            advanceUntilIdle()
            viewModel.selectPlace(manual)
            settleIo { !viewModel.uiState.value.isDetectingExifLocation }

            assertEquals(manual, viewModel.uiState.value.selectedPlace)
            assertEquals(null, viewModel.uiState.value.customLocationName)
            assertTrue(viewModel.uiState.value.isDetectingExifLocation.not())
        } finally {
            unmockkObject(ImageUtils)
        }
    }
}
