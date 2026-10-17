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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.litapp.pixlit.data.Fetch
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.data.repository.TagCount
import ovh.litapp.pixlit.data.work.UploadJob
import ovh.litapp.pixlit.data.work.UploadJobState
import ovh.litapp.pixlit.data.work.UploadJobStatus
import ovh.litapp.pixlit.data.work.UploadScheduler

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

    @Test
    fun `addImages updates selectedImageUris`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        val uris = listOf(uri1, uri2)
        
        viewModel.addImages(uris)
        advanceUntilIdle()
        
        assertEquals(uris, viewModel.selectedImageUris.value)
    }

    @Test
    fun `removeImageAt updates selectedImageUris`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1, uri2))
        advanceUntilIdle()
        
        viewModel.removeImageAt(0)
        advanceUntilIdle()
        
        assertEquals(listOf(uri2), viewModel.selectedImageUris.value)
    }

    @Test
    fun `shiftRight swaps images and updates currentPage`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1, uri2))
        advanceUntilIdle()
        
        viewModel.shiftRight(0)
        advanceUntilIdle()
        
        assertEquals(listOf(uri2, uri1), viewModel.selectedImageUris.value)
        assertEquals(1, viewModel.currentPage.value)
    }

    @Test
    fun `insertTag updates caption correctly`() = runTest {
        viewModel.insertTag("#photography (12)")
        assertEquals("#photography ", viewModel.captionState.value.text)
    }

    @Test
    fun `insertTagSuggestion replaces the hashtag being typed`() = runTest {
        viewModel.onCaptionChanged(
            TextFieldValue("A photo of #phot", TextRange("A photo of #phot".length))
        )

        viewModel.insertTagSuggestion("photography")

        assertEquals("A photo of #photography ", viewModel.captionState.value.text)
        assertEquals(
            "A photo of #photography ".length,
            viewModel.captionState.value.selection.start
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
        assertEquals(emptySet<String>(), viewModel.selectedCollectionIds.value)

        // Select 1 collection
        viewModel.toggleCollectionSelection("col1")
        assertEquals(setOf("col1"), viewModel.selectedCollectionIds.value)

        // Select second collection (multiple)
        viewModel.toggleCollectionSelection("col2")
        assertEquals(setOf("col1", "col2"), viewModel.selectedCollectionIds.value)

        // Deselect col1
        viewModel.toggleCollectionSelection("col1")
        assertEquals(setOf("col2"), viewModel.selectedCollectionIds.value)

        // Deselect col2 -> back to 0 collections
        viewModel.toggleCollectionSelection("col2")
        assertEquals(emptySet<String>(), viewModel.selectedCollectionIds.value)
    }

    @Test
    fun `createAndSelectCollection creates and selects new collection`() = runTest {
        val newCol = CollectionItem(id = JsonPrimitive("new_1"), title = "Architecture")
        coEvery { repository.createCollection("Architecture", null) } returns Fetch.Fresh(newCol)

        viewModel.createAndSelectCollection("Architecture")
        advanceUntilIdle()

        assert(viewModel.selectedCollectionIds.value.contains("new_1"))
        assert(viewModel.userCollections.value.any { it.getIdString() == "new_1" })
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
        assertEquals(emptySet<String>(), viewModel.selectedCollectionIds.value)
        assertEquals(false, viewModel.isUploading.value)
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
        assertEquals(listOf(updatedStatus), viewModel.recentStatuses.value)
        assertEquals(false, viewModel.isRefreshing.value)
    }

    @Test
    fun `selectPlace updates selectedPlace and clears custom location`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        assertEquals(place, viewModel.selectedPlace.value)
        assertEquals(null, viewModel.customLocationName.value)
        assertEquals(false, viewModel.isExifAutoDetected.value)
    }

    @Test
    fun `setCustomLocation updates customLocationName and clears selectedPlace`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        viewModel.setCustomLocation("Milano")

        assertEquals(null, viewModel.selectedPlace.value)
        assertEquals("Milano", viewModel.customLocationName.value)
        assertEquals(false, viewModel.isExifAutoDetected.value)
    }

    @Test
    fun `clearLocation clears all position selection state`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        viewModel.clearLocation()

        assertEquals(null, viewModel.selectedPlace.value)
        assertEquals(null, viewModel.customLocationName.value)
        assertEquals(false, viewModel.isExifAutoDetected.value)
    }

    @Test
    fun `onPlaceSearchQueryChanged performs debounced place search`() = runTest {
        val places = listOf(PlaceItem(id = JsonPrimitive("p10"), name = "Florence", country = "Italy"))
        coEvery { repository.searchPlaces(query = "Flor", lat = null, lng = null) } returns Fetch.Fresh(places)

        viewModel.onPlaceSearchQueryChanged("Flor")
        advanceTimeBy(301)
        advanceUntilIdle()

        assertEquals(places, viewModel.placeSearchResults.value)
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
}
